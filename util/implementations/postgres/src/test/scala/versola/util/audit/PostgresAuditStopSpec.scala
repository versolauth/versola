package versola.util.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import io.opentelemetry.api
import versola.util.SecureRandom
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.postgres.{AuditInsert, AuditTestTable, PostgresSpec}
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.{Instant, LocalDate}

/** The stop of the service against a real Postgres whose inserts are slow, closed as the app closes its scope (uninterruptibly) and on
  * the real clock. The driver's call holds a real thread and is not cut by interrupting it, and the transaction stays open in the
  * database while the stop goes on; none of that is in a stub. */
object PostgresAuditStopSpec extends PostgresSpec:

  private val table = AuditTestTable("audit_stop_spec")

  private val marker = "audit_stop_spec"

  private val kind = AuditKind("thing.done")

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** The repository of the copy, its inserts marked so that the spec can end them. */
  private class CopyRepository(xa: TransactorZIO) extends AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] =
      table.inTransaction(xa):
        sql"SELECT set_config('application_name', $marker, true)".query[String].run()
        AuditInsert.insertBatch(events)

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      ZIO.succeed(PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0))

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(TableOwnership(owner = "dev", role = "dev", mayAlter = true))

  private val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)

  private val sizing =
    AuditEvents.Sizing(queueCapacity = 5000, batchSize = 1000, flushInterval = 1.second, partitionsAhead = 3, maintenanceInterval = 1.hour)

  private def stopLines: UIO[List[String]] =
    ZTestLogger.logOutput.map(_.toList.map(_.message()).filter(_.contains("the audit queue was drained on shutdown")))

  val spec = suite("the stop of the service against Postgres")(
    test("with a batch of a thousand rows held in a ten-second statement and more queued, it returns inside the sum of its bounds, with its line") {
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- table.slowInserts(xa, millis = 10)
        scope <- Scope.make
        events <- scope.extend(
          AuditEvents
            .liveWith(sizing)(AuditSource("svc"), "node-1", AuditCatalog.of(Set(kind)))
            .build
            .map(_.get[AuditEvents])
            .provideSome[Scope](ZLayer.succeed[AuditEventRepository](CopyRepository(xa)), ZLayer.succeed(settings), tracingLayer, SecureRandom.live),
        )
        _ <- ZIO.foreachDiscard(1 to 1300)(_ => events.record(AuditDraft(kind = kind, outcome = AuditOutcome.success)))
        // The writer is inside a long statement by now.
        _ <- ZIO.sleep(1.second)
        started <- Clock.nanoTime
        closing <- ZIO.uninterruptible(scope.close(Exit.unit)).fork
        result <- closing.join.timeout(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(4.seconds))
        elapsed <- Clock.nanoTime.map(now => (now - started) / 1000000)
        lines <- stopLines
        _ <- table.endSessions(xa, marker)
      yield assertTrue(result.isDefined, elapsed < 9500L, lines.size == 1, lines.head.contains("events taken"))
    },
    test("and what was queued behind the held batch is stored by the stop, though the held rows wait on the statement that is still open") {
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- table.slowInserts(xa, millis = 10)
        scope <- Scope.make
        events <- scope.extend(
          AuditEvents
            .liveWith(sizing)(AuditSource("svc"), "node-1", AuditCatalog.of(Set(kind)))
            .build
            .map(_.get[AuditEvents])
            .provideSome[Scope](ZLayer.succeed[AuditEventRepository](CopyRepository(xa)), ZLayer.succeed(settings), tracingLayer, SecureRandom.live),
        )
        // One event first, so that the writer is inside its statement before the rest is queued behind it.
        _ <- events.record(AuditDraft(kind = kind, outcome = AuditOutcome.success))
        _ <- ZIO.sleep(500.millis)
        _ <- ZIO.foreachDiscard(1 to 1000)(_ => events.record(AuditDraft(kind = kind, outcome = AuditOutcome.success)))
        // The writer has taken the thousand into one statement of ten seconds; these are queued behind it.
        _ <- ZIO.sleep(1.second)
        _ <- ZIO.foreachDiscard(1 to 300)(_ => events.record(AuditDraft(kind = kind, outcome = AuditOutcome.success)))
        closing <- ZIO.uninterruptible(scope.close(Exit.unit)).fork
        result <- closing.join.timeout(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(4.seconds))
        stored <- table.count(xa, "audit_event")
        _ <- table.endSessions(xa, marker)
      yield assertTrue(result.isDefined, stored >= 250L)
    },
  ).provideSomeLayer[TransactorZIO](ZTestLogger.default) @@ TestAspect.sequential @@ TestAspect.before(
    table.reset,
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
