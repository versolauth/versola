package versola.central.audit

import io.opentelemetry.api
import versola.central.audit.CentralAudit
import versola.central.configuration.edges.EdgeService
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.SecureRandom
import versola.util.audit.*
import versola.util.partitions.{PartitionReport, TableOwnership}
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.{Instant, LocalDate}

/** What an administrator's request, a certificate event and a record of the first run do when the audit queue is full: the action
  * has happened, the answer is the normal one, and the record is one that cannot fail. The writer is the real one, on a repository
  * that does not answer, so that nothing it has taken leaves the queue. */
object AuditQueueFullSpec extends ZIOSpecDefault:

  private class Stuck(gate: Promise[Nothing, Unit]) extends AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] = gate.await
    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      ZIO.succeed(PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0))
    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)
    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)
    override def ownership: Task[TableOwnership] = ZIO.succeed(TableOwnership(owner = "central", role = "central", mayAlter = true))

  private val tracing: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val sizing = AuditEvents.Sizing(queueCapacity = 1, batchSize = 1, flushInterval = 1.hour, maintenanceInterval = 1.hour)

  /** The real writer with a queue of one, on a repository that never writes: after two events it is full. */
  private def fullQueue(overflow: AuditEvents.Overflow): ZIO[Scope, Nothing, AuditEvents] =
    for
      gate <- Promise.make[Nothing, Unit]
      events <- writer(overflow, gate)
      // the repository answers when the scope closes, which runs this before the writer's own stop
      _ <- ZIO.addFinalizer(gate.succeed(()))
    yield events

  private def writer(overflow: AuditEvents.Overflow, gate: Promise[Nothing, Unit]): ZIO[Scope, Nothing, AuditEvents] =
    val settings = AuditEvents.Settings(retention = 7.days, overflow = overflow, blockTimeout = 100.millis)
    val composed =
      ZLayer.succeed[AuditEventRepository](Stuck(gate)) ++ ZLayer.succeed(settings) ++ tracing ++ SecureRandom.live ++ ZLayer.service[Scope]
    (composed >>> AuditEvents.liveWith(sizing)(CentralAudit.Source, "test", CentralAudit.catalog, CentralAudit.Table)).build.map(_.get[AuditEvents])

  private val admin = "00000000-0000-0000-0000-0000000000f1"
  private val createClient = Method.POST / "configuration" / "clients" / "web"

  private def changeRequest(id: String) =
    Request.post(URL(Path.decode("/configuration/clients/web")), Body.fromString(s"""{"id":"$id"}"""))
      .updateURL(
        _.addQueryParam(AuditHook.ActorIdParam, admin)
          .addQueryParam(AuditHook.ActorTenantParam, "default")
          .addQueryParam(AuditHook.ActorClientParam, "central-admin"),
      )

  /** Ten changes through the hook, each of which hands back a secret (two events); how many the handler ran for and the statuses. */
  private def changes(events: AuditEvents) =
    for
      ran <- Ref.make(0)
      client <- ZIO.service[Client]
      routes = Routes(createClient -> handler((_: Request) => ran.update(_ + 1).as(Response.text("ok"))))
      _ <- TestClient.addRoutes((routes @@ TestAuditHook.middleware).provideEnvironment(ZEnvironment[AuditEvents, CentralConfig, EdgeService](
        events,
        TestCentralConfig.config,
        TestActor.registered,
      )))
      statuses <- ZIO.foreach(1 to 10)(n => TestActor.signed(changeRequest(s"c$n")).flatMap(client.batched(_).orDie.map(_.status)))
      handled <- ran.get
    yield (statuses, handled)

  def spec = suite("a full audit queue")(
    test("under drop the changes are answered as usual and every handler ran") {
      ZIO.scoped:
        for
          events <- fullQueue(AuditEvents.Overflow.drop)
          result <- changes(events)
        yield assertTrue(result._1.forall(_ == Status.Ok), result._2 == 10)
    },
    test("under block a change waits for the block timeout and is then answered as usual, never failed") {
      ZIO.scoped:
        for
          events <- fullQueue(AuditEvents.Overflow.block)
          // the queue is full from the third event on: each of the next ones waits 100 ms for room
          result <- changes(events)
        yield assertTrue(result._1.forall(_ == Status.Ok), result._2 == 10)
    },
    test("a certificate event and the record of the first run cannot fail on a full queue, under either setting") {
      ZIO.scoped:
        for
          dropping <- fullQueue(AuditEvents.Overflow.drop)
          _ <- ZIO.foreachDiscard(1 to 5)(_ =>
            CentralAudit.certificate(dropping, CentralAudit.CertRenewed, CentralAudit.Actors.Edge, Some("edge-1"), ("edge", "edge-1")),
          )
          blocking <- fullQueue(AuditEvents.Overflow.block)
          _ <- ZIO.foreachDiscard(1 to 5)(_ =>
            blocking.recordBestEffort(AuditDraft(
              kind = CentralAudit.SystemBootstrap,
              outcome = AuditOutcome.success,
              reason = None,
              actor = None,
              userId = None,
              target = None,
              details = AuditDetails.empty,
            )),
          )
        yield assertTrue(true)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.silentLogging @@ TestAspect.timeout(
    2.minutes,
  )
