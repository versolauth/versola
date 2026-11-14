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
  * What a scenario assumes it checks first: the writer is in the middle of a batch (at least two of its chunks are committed, and the
  * rest of the batch takes longer to write than the stop takes) before anything is queued behind it, so that the rows queued behind it
  * cannot have been stored by the writer; the rows the stop held are counted from the stop's own line; and the bounds are derived from
  * the cost of a row measured on the machine the spec runs on. */
object PostgresAuditStopSpec extends PostgresSpec:

  private val Table = "stop_spec_audit_event"

  private val table = AuditTestTable("audit_stop_spec", Table)

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
        AuditInsert.insertBatch(events, table = Table)

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      ZIO.succeed(PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0))

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(TableOwnership(owner = "dev", role = "dev", mayAlter = true))

  private val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)

  private val sizing =
    AuditEvents.Sizing(queueCapacity = 5000, batchSize = 1000, flushInterval = 1.second, partitionsAhead = 3, maintenanceInterval = 1.hour)

  private def draftWith(reason: String) = AuditDraft(
    kind = kind,
    outcome = AuditOutcome.success,
    reason = Some(reason),
    actor = None,
    userId = None,
    target = None,
    details = AuditDetails.empty,
  )

  private def countReason(xa: TransactorZIO, reason: String): Task[Long] =
    table.inTransaction(xa)(sql"SELECT COUNT(*) FROM ${SqlLiteral(Table)} WHERE reason = $reason".query[Long].run().head)

  /** Waits, in real time and with a bound, until `rows` of the writer's batch are committed, which is to say it is in the middle of it. */
  private def awaitCommitted(xa: TransactorZIO, rows: Long): Task[Long] =
    (ZIO.sleep(50.millis) *> countReason(xa, "held"))
      .repeatUntil(_ >= rows)
      .timeoutFail(RuntimeException(s"the writer had not committed $rows rows within 30 s"))(30.seconds)

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

  private def warnings: UIO[List[String]] =
    ZTestLogger.logOutput.map(_.toList.filter(_.logLevel >= LogLevel.Warning).map(_.message()))

  /** The counts of the queued and the held rows once nothing is committing any more. The stop abandons, and does not wait for, a statement
    * the transactor will not interrupt, so the writer's chunk in flight when the stop began and the drain's chunk in flight at its limit
    * both commit after the stop has returned, each about `writeChunk` rows at the cost of a row. A count taken at the moment the stop
    * returns is therefore a count at a moment that depends on how fast the machine is. Waits, bounded, until both counts have not moved
    * for two seconds, longer than such a statement takes. */
  private def settled(xa: TransactorZIO): Task[(Long, Long)] =
    def counts = countReason(xa, "queued").zip(countReason(xa, "held"))
    def go(last: (Long, Long), stillFor: Int, polls: Int): Task[(Long, Long)] =
      ZIO.sleep(250.millis) *> counts.flatMap: now =>
        if now == last && stillFor >= 7 then ZIO.succeed(now)
        else if polls >= 80 then ZIO.succeed(now)
        else go(now, if now == last then stillFor + 1 else 0, polls + 1)
    counts.flatMap(go(_, 0, 0))

  private val taken = raw"(\d+) events taken, (\d+) not written".r.unanchored

  private case class Outcome(
      returned: Boolean,
      elapsedMillis: Long,
      committedBefore: Long,
      held: Long,
      notWritten: Long,
      queuedStored: Long,
      heldBefore: Long,
      heldAfter: Long,
      /** The same two counts once the abandoned statements have ended (see [[settled]]): what the database ends up holding. */
      queuedSettled: Long,
      heldSettled: Long,
      perRowMillis: Double,
      /** The stop's own line and every warning the service logged up to the end of the scenario: what a failed check needs to say why
        * the stop wrote less than the window carries (a chunk that failed is named there by its exception classes and SQLSTATE). */
      logged: List[String],
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
          .liveWith(sizing)(source, "node-1", AuditCatalog.of(Set(kind)), Table)
          .build
          .map(_.get[AuditEvents])
          .provideSome[Scope](
            ZLayer.succeed[AuditEventRepository](repository),
            ZLayer.succeed(settings),
            ZLayer.succeed[AuditTenants](AuditTenants.none),
            tracingLayer,
            SecureRandom.live,
          ),
      )
      // One event, then a burst: the writer takes the first at once and the rest as one batch when it is done with it.
      _ <- events.record(draftWith("held")).ignore
      _ <- ZIO.foreachDiscard(1 to burst)(_ => events.record(draftWith("held")).ignore)
      committed <- awaitCommitted(xa, rows = 200)
      // The writer is in the middle of its batch now, and has more of it than the stop takes to begin: what is queued from here on can
      // only be stored by the stop.
      _ <- ZIO.foreachDiscard(1 to queued)(_ => events.record(draftWith("queued")).ignore)
      heldBefore <- countReason(xa, "held")
      started <- Clock.nanoTime
      closing <- ZIO.uninterruptible(scope.close(Exit.unit)).fork
      result <- closing.join.timeout(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(4.seconds))
      elapsed <- Clock.nanoTime.map(now => (now - started) / 1000000)
      lines <- stopLines
      warned <- warnings
      queuedStored <- countReason(xa, "queued")
      heldAfter <- countReason(xa, "held")
      ended <- settled(xa)
      _ <- table.endSessions(xa, marker)
      counts = lines.headOption.collect { case taken(all, left) => (all.toLong, left.toLong) }.getOrElse((0L, 0L))
    yield Outcome(
      returned = result.isDefined,
      elapsedMillis = elapsed,
      committedBefore = committed,
      held = counts._1 - queued,
      notWritten = counts._2,
      queuedStored = queuedStored,
      heldBefore = heldBefore,
      heldAfter = heldAfter,
      queuedSettled = ended._1,
      heldSettled = ended._2,
      perRowMillis = perRow,
      logged = lines ++ warned,
    )

  /** What the window can carry at the cost measured: the rows the stop could write in `DrainTimeout`, and half of that as the bound the
    * spec asks of it, so that a slow machine lowers the bound and does not fail the spec. */
  private def required(outcome: Outcome, queued: Int): Long =
    math.min(queued.toLong, (0.5 * AuditEvents.DrainTimeout.toMillis / outcome.perRowMillis).toLong)

  private def check(outcome: Outcome, queued: Int) =
    val result = assertTrue(
      // The scenario is the one it claims to be: the writer was in the middle of its batch when the stop began.
      outcome.committedBefore >= 200L,
      outcome.held >= 100L,
      // The stop returned inside the sum of its bounds, with its line.
      outcome.returned,
      outcome.elapsedMillis < 9500L,
      // The rows queued behind the writer's statement are stored by the stop, as many as the window carries.
      outcome.queuedStored >= required(outcome, queued),
      // And the held rows come after them: the stop has stored none of them before every queued row. Once the abandoned statements have
      // ended, either every queued row is stored, or the held count moved only by those statements: the writer's chunk in flight when
      // the stop began and the drain's chunk in flight at its limit, a chunk each and no more. (Read at the moment the stop returns it
      // moved by more on a slow machine, 234 rows against a bound of 100, because the second chunk had not committed yet.)
      outcome.queuedSettled == queued.toLong || (outcome.heldSettled - outcome.heldBefore) <= 2L * sizing.writeChunk,
    )
    // Timings decide these checks, and a failure on a loaded machine is otherwise only a number: say what was measured.
    result ?? s"queued=$queued, required=${required(outcome, queued)}, $outcome"

  val spec = suite("the stop of the service against Postgres")(
    test("at ten milliseconds a row, with a batch in progress, the stop returns by its bounds and stores what was queued behind it") {
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
