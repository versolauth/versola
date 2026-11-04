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
  * database while the stop goes on; none of that is in a stub.
  *
  * What a scenario assumes it checks first: the writer's statement is open in the database, for at least two seconds, before anything
  * is queued behind it, so that the rows queued behind it cannot have been stored by the writer; the rows the stop held are counted
  * from the stop's own line; and the bounds are derived from the cost of a row measured on the machine the spec runs on. */
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

  /** The repository of the copy, its inserts marked so that the spec can find them in `pg_stat_activity` and end them. */
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

  private def draftWith(reason: String) = AuditDraft(kind = kind, outcome = AuditOutcome.success, reason = Some(reason))

  private def countReason(xa: TransactorZIO, reason: String): Task[Long] =
    table.inTransaction(xa)(sql"SELECT COUNT(*) FROM audit_event WHERE reason = $reason".query[Long].run().head)

  /** How long the oldest open statement of the writer's sessions has been running, in seconds; 0 when there is none. */
  private def openFor(xa: TransactorZIO): Task[Double] =
    table.inTransaction(xa):
      sql"""
        SELECT COALESCE(max(EXTRACT(EPOCH FROM (now() - xact_start))), 0)::float8 FROM pg_stat_activity
        WHERE application_name = $marker AND state = 'active'
      """.query[Double].run().head

  /** Waits, in real time and with a bound, until the writer's statement has been open for `seconds`. */
  private def awaitOpen(xa: TransactorZIO, seconds: Double): Task[Double] =
    (ZIO.sleep(100.millis) *> openFor(xa))
      .repeatUntil(_ >= seconds)
      .timeoutFail(RuntimeException(s"the writer's statement was not open for $seconds s within 30 s"))(30.seconds)

  private def calibrationEvent(): AuditEvent =
    AuditEvent(
      id = java.util.UUID.randomUUID(),
      at = Instant.parse("2026-11-04T10:00:00Z"),
      tenantId = None,
      source = AuditSource("svc"),
      nodeId = "node-1",
      kind = kind,
      outcome = AuditOutcome.success,
      reason = Some("calibration"),
      severity = AuditSeverity.info,
      actorType = ActorType.system,
      actorId = None,
      userId = None,
      clientId = None,
      sessionId = None,
      ip = None,
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = zio.json.ast.Json.Obj(),
    )

  /** The milliseconds one row costs on this machine, with the trigger: a hundred rows in a transaction of their own. */
  private def calibrate(repository: AuditEventRepository): Task[Double] =
    for
      started <- Clock.nanoTime
      _ <- repository.insertBatch(List.fill(100)(calibrationEvent()))
      finished <- Clock.nanoTime
    yield (finished - started) / 1e6 / 100

  private def stopLines: UIO[List[String]] =
    ZTestLogger.logOutput.map(_.toList.map(_.message()).filter(_.contains("the audit queue was drained on shutdown")))

  private val taken = raw"(\d+) events taken, (\d+) not written".r.unanchored

  private case class Outcome(
      returned: Boolean,
      elapsedMillis: Long,
      openSeconds: Double,
      held: Long,
      notWritten: Long,
      queuedStored: Long,
      heldBefore: Long,
      heldAfter: Long,
      perRowMillis: Double,
  )

  /** A writer inside a statement of many rows, at `millis` a row; `queued` rows queued behind it once it has been open for two seconds;
    * the scope closed uninterruptibly. */
  private def scenario(xa: TransactorZIO, millis: Int, burst: Int, queued: Int): Task[Outcome] =
    val source = AuditSource("svc")
    val repository = CopyRepository(xa)
    for
      _ <- table.slowInserts(xa, millis)
      perRow <- calibrate(repository)
      scope <- Scope.make
      events <- scope.extend(
        AuditEvents
          .liveWith(sizing)(source, "node-1", AuditCatalog.of(Set(kind)))
          .build
          .map(_.get[AuditEvents])
          .provideSome[Scope](ZLayer.succeed[AuditEventRepository](repository), ZLayer.succeed(settings), tracingLayer, SecureRandom.live),
      )
      // One event, then a burst: the writer takes the first at once and the rest as one batch when it is done with it.
      _ <- events.record(draftWith("held")).ignore
      _ <- ZIO.foreachDiscard(1 to burst)(_ => events.record(draftWith("held")).ignore)
      open <- awaitOpen(xa, seconds = 2.0)
      // The writer is stuck in its statement now: what is queued from here on can only be stored by the stop.
      _ <- ZIO.foreachDiscard(1 to queued)(_ => events.record(draftWith("queued")).ignore)
      heldBefore <- countReason(xa, "held")
      started <- Clock.nanoTime
      closing <- ZIO.uninterruptible(scope.close(Exit.unit)).fork
      result <- closing.join.timeout(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(4.seconds))
      elapsed <- Clock.nanoTime.map(now => (now - started) / 1000000)
      lines <- stopLines
      queuedStored <- countReason(xa, "queued")
      heldAfter <- countReason(xa, "held")
      _ <- table.endSessions(xa, marker)
      counts = lines.headOption.collect { case taken(all, left) => (all.toLong, left.toLong) }.getOrElse((0L, 0L))
    yield Outcome(
      returned = result.isDefined,
      elapsedMillis = elapsed,
      openSeconds = open,
      held = counts._1 - queued,
      notWritten = counts._2,
      queuedStored = queuedStored,
      heldBefore = heldBefore,
      heldAfter = heldAfter,
      perRowMillis = perRow,
    )

  /** What the window can carry at the cost measured: the rows the stop could write in `DrainTimeout`, and half of that as the bound the
    * spec asks of it, so that a slow machine lowers the bound and does not fail the spec. */
  private def required(outcome: Outcome, queued: Int): Long =
    math.min(queued.toLong, (0.5 * AuditEvents.DrainTimeout.toMillis / outcome.perRowMillis).toLong)

  private def check(outcome: Outcome, queued: Int) =
    assertTrue(
      // The scenario is the one it claims to be: the writer was in a long statement when the stop began.
      outcome.openSeconds >= 2.0,
      outcome.held >= 100L,
      // The stop returned inside the sum of its bounds, with its line.
      outcome.returned,
      outcome.elapsedMillis < 9500L,
      // The rows queued behind the writer's statement are stored by the stop, as many as the window carries.
      outcome.queuedStored >= required(outcome, queued),
      // And the held rows come after them: the stop has stored none of them before every queued row, so the held count either did not
      // move, or moved by exactly the held batch, which is the abandoned statement committing on its own, never by a part of it.
      outcome.queuedStored == queued.toLong || (outcome.heldAfter - outcome.heldBefore) == 0L || (outcome.heldAfter - outcome.heldBefore) == outcome.held,
    )

  val spec = suite("the stop of the service against Postgres")(
    test("at ten milliseconds a row, with a long statement in progress, the stop returns by its bounds and stores what was queued behind it") {
      for
        xa <- ZIO.service[TransactorZIO]
        outcome <- scenario(xa, millis = 10, burst = 1000, queued = 300)
      yield check(outcome, queued = 300)
    },
    test("at three milliseconds a row, with more queued, the stop stores as many of them as its window carries") {
      for
        xa <- ZIO.service[TransactorZIO]
        outcome <- scenario(xa, millis = 3, burst = 1000, queued = 1171)
      yield check(outcome, queued = 1171)
    },
  ).provideSomeLayer[TransactorZIO](ZTestLogger.default) @@ TestAspect.sequential @@ TestAspect.before(
    table.reset,
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(180.seconds)
