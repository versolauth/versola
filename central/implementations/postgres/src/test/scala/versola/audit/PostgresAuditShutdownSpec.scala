package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import io.opentelemetry.api
import versola.PostgresCentralApp
import versola.central.audit.CentralAudit
import versola.util.SecureRandom
import versola.util.audit.*
import versola.util.http.VersolaApp
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** What the writer holds when the process is told to stop is written before the pool closes: the layers are composed as
  * `PostgresCentralApp` composes them (the transactor, the repository on it, the writer on the repository), the scope is closed the way
  * the app's is when it stops, and the rows of `central_audit_event` are counted. Each case marks its events with a target of its own, so it
  * counts nothing but its own rows and the other specs of the table are left alone. */
object PostgresAuditShutdownSpec extends PostgresSpec:

  private val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)

  /** A batch size far under the number of events, and a writer that rests longer than any case lasts after a batch that did not fill. */
  private val sizing = AuditEvents.Sizing(batchSize = 10, flushInterval = 1.hour, maintenanceInterval = 1.hour)

  private val tracing: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def draft(marker: String) = AuditDraft(
    kind = CentralAudit.admin("client", "create"),
    outcome = AuditOutcome.success,
    actor = Some((CentralAudit.Actors.Admin, Some("shutdown-check"))),
    target = Some(("client", marker)),
  )

  /** The repository of the app, and what it was given: `insertBatch` waits `delay` first, and says when a write has started. */
  private class Delayed(inner: AuditEventRepository, delay: Duration, started: Promise[Nothing, Unit]) extends AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] = started.succeed(()) *> ZIO.sleep(delay) *> inner.insertBatch(events)
    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      inner.maintainPartitions(from, days, keepFrom)
    override def partitionDaysAhead(today: LocalDate): Task[Int] = inner.partitionDaysAhead(today)
    override def pruneDefault(before: Instant): Task[Long] = inner.pruneDefault(before)
    override def ownership: Task[TableOwnership] = inner.ownership

  /** The rows of the case, and how many of them are different events. */
  private def stored(marker: String) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        val rows = sql"SELECT count(*) FROM central_audit_event WHERE target_id = $marker".query[Long].run().head
        val distinct = sql"SELECT count(DISTINCT id) FROM central_audit_event WHERE target_id = $marker".query[Long].run().head
        (rows, distinct)

  /** The writer of the app on `repository`, in a scope of its own that is closed when `use` is done: what the app's scope does on SIGTERM
    * once the servers have stopped. */
  private def stopped[A](repository: AuditEventRepository)(use: AuditEvents => Task[A]): ZIO[SecureRandom, Throwable, A] =
    for
      random <- ZIO.service[SecureRandom]
      scope <- Scope.make
      composed = ZLayer.succeed(repository) ++ ZLayer.succeed(settings) ++ tracing ++ ZLayer.succeed(random) ++ ZLayer.succeed(scope)
      writer <- (composed >>> PostgresCentralApp.auditWriter(sizing)).build.provideEnvironment(ZEnvironment(scope)).map(_.get[AuditEvents])
      result <- use(writer)
      _ <- scope.close(Exit.unit)
    yield result

  def spec = suite("the audit log of central when the process stops")(
    test("every event the queue accepted is in the table once the scope is closed, none twice") {
      val marker = UUID.randomUUID().toString
      val total = 45
      for
        xa <- ZIO.service[TransactorZIO]
        repository = PostgresAuditEventRepository(xa)
        _ <- stopped(repository)(events =>
          ZIO.foreachDiscard(1 to total)(_ => events.record(draft(marker)).mapError(_ => RuntimeException("unavailable"))),
        )
        counted <- stored(marker)
      yield assertTrue(counted == (total.toLong, total.toLong))
    }.provideSome[TransactorZIO](SecureRandom.live),
    test("a stop that arrives while a write is under way writes the events of that write as well") {
      val marker = UUID.randomUUID().toString
      val total = 25
      for
        xa <- ZIO.service[TransactorZIO]
        started <- Promise.make[Nothing, Unit]
        repository = Delayed(PostgresAuditEventRepository(xa), delay = 300.millis, started = started)
        _ <- stopped(repository): events =>
          ZIO.foreachDiscard(1 to total)(_ => events.record(draft(marker)).mapError(_ => RuntimeException("unavailable"))) *> started.await
        counted <- stored(marker)
      yield assertTrue(counted == (total.toLong, total.toLong))
    }.provideSome[TransactorZIO](SecureRandom.live),
    test("the time the writer is given to drain, and a margin to stop the servers and close the pool, is within the app's shutdown timeout") {
      val margin = 5.seconds
      assertTrue(AuditEvents.DrainTimeout + margin <= VersolaApp.GracefulShutdownTimeout)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)
