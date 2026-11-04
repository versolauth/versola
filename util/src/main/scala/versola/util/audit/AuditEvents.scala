package versola.util.audit

import versola.util.SecureRandom
import versola.util.http.Observability
import versola.util.http.Observability.AuthDetails
import zio.*
import zio.json.ast.Json
import zio.logging.logContext
import zio.metrics.{Metric, MetricLabel}
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.util.Try

/** The audit log, as the services see it: a record of what happened, which the request that made it happen may have to wait
  * for. `record` fills a draft in from the request's context and hands it to a bounded in-memory queue; a background writer
  * takes what has gathered and writes it in batches, and keeps a batch that could not be written until it can. With
  * `overflow = block` a full queue holds the request up to `blockTimeout` and then fails it (the log is the gate: an event that
  * cannot be recorded is a request that does not happen); with `drop` the event is dropped and counted. */
trait AuditEvents:
  /** Fails only with [[AuditUnavailable]], and only under `overflow = block` when the queue stayed full for `blockTimeout`: the
    * caller answers 503 `audit_unavailable` (`setError`). */
  def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit]

  /** `record` that cannot fail: for a path that must not be held up by the log (an action that has already happened). Under
    * `block` it waits as `record` does and then drops the event, counted. */
  def recordBestEffort(draft: AuditDraft): UIO[Unit]

/** The audit log could not take the event in time. */
case object AuditUnavailable

object AuditEvents:

  /** What a deployment sets for the log (the service's `audit` block of its config; the kinds a deployment may leave out are the
    * service's own keys next to these).
    *
    * @param retention    how long the log is kept: a day's partition is dropped once the whole day is older than this
    * @param overflow     what a full queue does to the request: holds it ([[Overflow.block]]) or drops the event ([[Overflow.drop]])
    * @param blockTimeout how long a request waits for room in the queue under `block` before it fails
    */
  case class Settings(retention: Duration, overflow: Overflow, blockTimeout: Duration)

  enum Overflow:
    case block, drop

  /** What is not configuration: the sizes and the pace of the writer and the maintenance.
    *
    * @param queueCapacity       events held while the writer catches up; past it a request blocks or an event is dropped
    * @param batchSize           the most events written in one round trip
    * @param flushInterval       the writer rests this long after a batch that did not fill, and between attempts at a batch that
    *                            could not be written
    * @param partitionsAhead     days of partitions created beyond today, so a maintenance run that is late does not leave the
    *                            writer without one
    * @param maintenanceInterval how often the partitions are made ahead, the old ones dropped and the default partition pruned
    * @param reportWindow       how often the events the log dropped or refused are written to the service's log: one line per window, whatever the number
    */
  case class Sizing(
      queueCapacity: Int = 50000,
      batchSize: Int = 1000,
      flushInterval: Duration = 1.second,
      partitionsAhead: Int = 14,
      maintenanceInterval: Duration = 1.hour,
      reportWindow: Duration = 1.minute,
  )

  object Sizing:
    val standard: Sizing = Sizing()

  /** Records nothing. For tests of what calls it, and for a service that has no log. */
  object noop extends AuditEvents:
    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = ZIO.unit

    override def recordBestEffort(draft: AuditDraft): UIO[Unit] = ZIO.unit

  /** Starts the writer and the partition maintenance; both stop with the scope, and what is still queued is written once more on
    * the way out, within a bound. `source`, `nodeId` and `catalog` are the service's: every event it records carries the first two,
    * and is rated and counted by the third. */
  def live(
      source: AuditSource,
      nodeId: String,
      catalog: AuditCatalog,
  ): ZLayer[AuditEventRepository & Settings & Tracing & SecureRandom & Scope, Nothing, AuditEvents] =
    liveWith(Sizing.standard)(source, nodeId, catalog)

  def liveWith(
      sizing: Sizing,
  )(
      source: AuditSource,
      nodeId: String,
      catalog: AuditCatalog,
  ): ZLayer[AuditEventRepository & Settings & Tracing & SecureRandom & Scope, Nothing, AuditEvents] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AuditEventRepository]
        settings <- ZIO.service[Settings]
        tracing <- ZIO.service[Tracing]
        random <- ZIO.service[SecureRandom]
        // A back-pressure queue under `block`, so that an offer waits for room; a dropping one under `drop`, so that it does not.
        queue <- settings.overflow match
          case Overflow.block => Queue.bounded[AuditEvent](sizing.queueCapacity)
          case Overflow.drop => Queue.dropping[AuditEvent](sizing.queueCapacity)
        _ <- AuditMetrics.register(source)
        intake <- Ref.make(Intake(closed = false, inflight = 0))
        inHand <- Ref.make(List.empty[AuditEvent])
        report <- EventReport.make(source, catalog, sizing.reportWindow)
        _ <- Maintenance(repository, settings, sizing, source).run
        // Before the writer, so that its last report is made after the writer's last drain.
        _ <- report.run
        _ <- Writer(queue, repository, sizing, source, report, intake, inHand).run
      yield Impl(
        intake = intake,
        report = report,
        queue = queue,
        settings = settings,
        sizing = sizing,
        source = source,
        nodeId = nodeId,
        catalog = catalog,
        tracing = tracing,
        random = random,
      )

  /** Whether the service has begun to stop, and how many events are being offered to the queue: the stop waits for those before it
    * takes the last of the queue, and what is offered after it began is refused. */
  private case class Intake(closed: Boolean, inflight: Int)

  private class Impl(
      intake: Ref[Intake],
      report: EventReport,
      queue: Queue[AuditEvent],
      settings: Settings,
      sizing: Sizing,
      source: AuditSource,
      nodeId: String,
      catalog: AuditCatalog,
      tracing: Tracing,
      random: SecureRandom,
  ) extends AuditEvents:

    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] =
      fill(draft).flatMap(admitted(_, bestEffort = false))

    override def recordBestEffort(draft: AuditDraft): UIO[Unit] =
      fill(draft).flatMap(admitted(_, bestEffort = true)).catchAll(_ => ZIO.unit)

    /** An event is offered to the queue while the service is running; once it has begun to stop, nothing is accepted: under `block`
      * the request fails as for a queue that stayed full, otherwise the event is dropped and counted. */
    private def admitted(event: AuditEvent, bestEffort: Boolean): IO[AuditUnavailable.type, Unit] =
      intake.modify(state => if state.closed then (false, state) else (true, state.copy(inflight = state.inflight + 1))).flatMap:
        case true => enqueue(event, bestEffort).ensuring(intake.update(state => state.copy(inflight = state.inflight - 1)))
        case false =>
          if settings.overflow == Overflow.block && !bestEffort then AuditMetrics.unavailable(source) *> ZIO.fail(AuditUnavailable)
          else AuditMetrics.dropped(source) *> report.note(EventReport.AfterStop, event.kind)

    private def enqueue(event: AuditEvent, bestEffort: Boolean): IO[AuditUnavailable.type, Unit] =
      settings.overflow match
        case Overflow.drop =>
          queue.offer(event).flatMap:
            case true => AuditMetrics.recorded(source, event, catalog)
            case false => AuditMetrics.dropped(source) *> report.note(EventReport.QueueFull, event.kind)
        case Overflow.block =>
          // The size is read before the offer so that a request that had to wait is counted, whether or not it got room in time.
          queue.size.flatMap: size =>
            ZIO.when(size >= sizing.queueCapacity)(AuditMetrics.blocked(source)) *>
              queue.offer(event).timeout(settings.blockTimeout).flatMap:
                case Some(_) => AuditMetrics.recorded(source, event, catalog)
                case None if bestEffort => AuditMetrics.dropped(source) *> report.note(EventReport.WaitedForRoom, event.kind)
                case None => AuditMetrics.unavailable(source) *> ZIO.fail(AuditUnavailable)

    /** The event of a draft: ids and time, then the request's context, from [[AuditContext]] first and the `auth` log context
      * second, for what the draft does not say. */
    private def fill(draft: AuditDraft): UIO[AuditEvent] =
      for
        id <- random.nextUUIDv7
        now <- Clock.instant
        auth <- logContext.get.map(_.get(Observability.auth).getOrElse(AuthDetails.empty))
        context <- AuditContext.current
        span <- tracing.getCurrentSpanContextUnsafe
      yield
        val userId = draft.userId.orElse(context.userId).orElse(auth.userId.flatMap(id => Try(UUID.fromString(id)).toOption))
        val (actorType, actorId) = draft.actor.orElse(context.actor).getOrElse(
          userId.fold((ActorType.system, None))(id => (ActorType.user, Some(id.toString))),
        )
        val target = draft.target.orElse(context.target)
        val details = draft.details.toJson
        AuditSanitizer(AuditEvent(
          id = id,
          at = now,
          tenantId = context.tenantId,
          source = source,
          nodeId = nodeId,
          kind = draft.kind,
          outcome = draft.outcome,
          reason = draft.reason,
          severity = catalog.severity(draft.kind, draft.outcome),
          actorType = actorType,
          actorId = actorId,
          userId = userId,
          clientId = auth.clientId,
          // The session the request established, else the one it arrived with.
          sessionId = auth.sessionId.orElse(auth.priorSessionId),
          ip = auth.ip,
          userAgent = context.userAgent,
          traceId = Option.when(span.isValid)(span.getTraceId),
          targetType = target.map(_._1),
          targetId = target.map(_._2),
          // What a service noted it changed goes with the event unless the draft already says.
          details =
            if context.changed.isEmpty || details.contains(ChangedField) then details
            else Json.Obj(details.fields :+ (ChangedField -> Json.Arr(Chunk.fromIterable(context.changed.toList.sorted).map(Json.Str(_))))*),
        ))

  /** The member of `details` that lists the fields an update changed, from [[AuditContext.setChanged]]. */
  val ChangedField = "changed"

  /** Batches tried, one `flushInterval` apart, before a batch that keeps failing is written row by row. */
  private val BatchAttempts = 3

  /** The longest rest between rounds while the database is failing. */
  private val MaxBackoff: Duration = 30.seconds

  private class Writer(
      queue: Queue[AuditEvent],
      repository: AuditEventRepository,
      sizing: Sizing,
      source: AuditSource,
      report: EventReport,
      intake: Ref[Intake],
      inHand: Ref[List[AuditEvent]],
  ):

    /** Takes what has gathered, writes it, rests, and again. A backlog is written back to back, so the write rate is not capped at
      * one batch per interval. What a batch that fails does is [[write]]'s. The batch being written is kept in `inHand` from the moment
      * it is taken, in one uninterruptible step with the take, so that a stop finds it. */
    private def loop(failing: Ref[Boolean]): UIO[Nothing] =
      (for
        batch <- ZIO.uninterruptibleMask: restore =>
          restore(queue.take).flatMap: first =>
            queue.takeUpTo(sizing.batchSize - 1).flatMap: rest =>
              val taken = (first +: rest).toList
              inHand.set(taken).as(taken)
        _ <- AuditMetrics.queueSize(source, queue)
        _ <- write(batch, failing, round = 0)
        _ <- inHand.set(Nil)
        _ <- ZIO.sleep(sizing.flushInterval).when(batch.size < sizing.batchSize)
      yield ()).forever

    /** Until every event of the batch is stored or refused. A batch that fails is tried again, a few times, a `flushInterval` apart;
      * a batch that still fails is written row by row, which stores the rows the database takes and finds the ones it refuses for
      * what is in them (see [[refusedForItself]]): those are dropped and counted. The rows that failed for another reason (the
      * database is down, a statement timed out) are tried again, with a growing rest, for as long as it takes; meanwhile the queue
      * fills and requests block or events are dropped, by the setting. */
    private def write(batch: List[AuditEvent], failing: Ref[Boolean], round: Int): UIO[Unit] =
      attemptBatch(batch, failing, attempt = 1).flatMap:
        case true => recovered(failing)
        case false =>
          rowByRow(batch).flatMap: left =>
            if left.isEmpty then recovered(failing)
            else inHand.set(left) *> ZIO.sleep(backoff(round)) *> write(left, failing, round + 1)

    private def backoff(round: Int): Duration =
      val rest = sizing.flushInterval.multipliedBy(1L << math.min(round, 5))
      if rest.compareTo(MaxBackoff) > 0 then MaxBackoff else rest

    /** The warning, with its stack, is logged once when writes start failing and once more when they recover, not for every
      * attempt in between. */
    private def attemptBatch(batch: List[AuditEvent], failing: Ref[Boolean], attempt: Int): UIO[Boolean] =
      repository.insertBatch(batch).foldCauseZIO(
        cause =>
          failing.getAndSet(true).flatMap(was =>
            ZIO.logWarningCause(s"could not write ${batch.size} audit events; they are kept and tried again", cause).unless(was),
          ) *> (
            if attempt >= BatchAttempts then ZIO.succeed(false)
            else ZIO.sleep(sizing.flushInterval) *> attemptBatch(batch, failing, attempt + 1)
          ),
        _ => ZIO.succeed(true),
      )

    private def recovered(failing: Ref[Boolean]): UIO[Unit] =
      failing.getAndSet(false).flatMap(was => ZIO.logInfo("audit events are being written again").when(was)).unit

    /** Each event on its own: the ones stored are done, the ones refused for themselves are dropped and counted (and reported once per
      * window by kind, never by row), and the rest are returned to be tried again. A batch is one transaction, so nothing of a
      * failed batch was stored and no event is written twice. */
    private def rowByRow(batch: List[AuditEvent]): UIO[List[AuditEvent]] =
      ZIO.foreach(batch)(event => repository.insertBatch(List(event)).either.map(event -> _)).flatMap: results =>
        val stored = results.exists(_._2.isRight)
        val failed = results.collect { case (event, Left(error)) => (event, error) }
        val (refused, left) = failed.partition((_, error) => refusedForItself(error, othersStored = stored))
        ZIO.when(refused.nonEmpty)(
          AuditMetrics.rejected(source, refused.size) *>
            report.noteAll(EventReport.Refused, refused.map(_._1)) *>
            report.noteStates(refused.flatMap((_, error) => sqlStates(error))),
        ).as(left.map(_._1))

    /** What the service does when it stops, within [[DrainTimeout]]: nothing more is accepted, the writer is stopped, and the batch it
      * held together with what is queued is written, again and again while the repository fails, until it is stored, refused for
      * what it contains, or the time is up; what was not written then is counted. Writing the held batch again is safe whether or
      * not the statement that was interrupted had committed, since a row is written once however often it is offered. One line says
      * the queue was drained, whatever it held. */
    private def stop(writer: Fiber[Nothing, Nothing]): UIO[Unit] =
      for
        _ <- intake.update(_.copy(closed = true))
        _ <- writer.interrupt
        held <- inHand.getAndSet(Nil)
        pending <- Ref.make(held)
        taken <- Ref.make(held.size)
        finished <- drainRound(pending, taken).timeout(DrainTimeout)
        rest <- queue.takeAll
        left <- pending.get.map(_ ++ rest)
        count <- taken.get.map(_ + rest.size)
        _ <- ZIO.when(left.nonEmpty)(AuditMetrics.failed(source, left.size) *> report.noteAll(EventReport.NotWritten, left))
        _ <- ZIO.logInfo(
          s"the audit queue was drained on shutdown: $count events taken, ${left.size} not written" +
            (if finished.isEmpty then s" (the write did not finish in $DrainTimeout)" else ""),
        )
      yield ()

    /** Writes what is pending and what has been queued since, until nothing is being offered and nothing is queued. */
    private def drainRound(pending: Ref[List[AuditEvent]], taken: Ref[Int]): UIO[Unit] =
      for
        queued <- queue.takeAll
        _ <- taken.update(_ + queued.size)
        _ <- pending.update(_ ++ queued)
        rows <- pending.get
        _ <- ZIO.foreachDiscard(rows.grouped(sizing.batchSize).toList)(writeUntilDone(_, pending))
        idle <- intake.get.map(_.inflight == 0)
        empty <- queue.size.map(_ <= 0)
        _ <- if idle && empty then ZIO.unit else ZIO.yieldNow *> drainRound(pending, taken)
      yield ()

    /** The rows of `chunk` are removed from `pending` as they are stored or refused; the rest are tried again a `flushInterval` later. */
    private def writeUntilDone(chunk: List[AuditEvent], pending: Ref[List[AuditEvent]]): UIO[Unit] =
      def done(rows: List[AuditEvent]): UIO[Unit] =
        val ids = rows.map(_.id).toSet
        pending.update(_.filterNot(event => ids.contains(event.id)))
      repository.insertBatch(chunk).foldZIO(
        _ =>
          rowByRow(chunk).flatMap: left =>
            val leftIds = left.map(_.id).toSet
            done(chunk.filterNot(event => leftIds.contains(event.id))) *>
              ZIO.when(left.nonEmpty)(ZIO.sleep(sizing.flushInterval) *> writeUntilDone(left, pending)).unit
        ,
        _ => done(chunk),
      )

    val run: URIO[Scope, Unit] =
      for
        failing <- Ref.make(false)
        // Not `fork`: that would tie the writer to the fiber that builds the layer, which ends once it is built.
        fiber <- loop(failing).forkDaemon
        // The queue's size is read on a clock of its own: it is set only when the writer takes something otherwise, and a writer
        // held by a failing write takes nothing.
        _ <- (AuditMetrics.queueSize(source, queue) *> ZIO.sleep(sizing.flushInterval)).forever.forkScoped
        _ <- ZIO.addFinalizer(stop(fiber))
      yield ()

  val DrainTimeout: Duration = 5.seconds

  /** The events the log dropped or refused, written to the service's log once per window and not once per event. Each is also counted
    * (`audit_events_dropped_total`, `audit_events_rejected_total`, `audit_events_failed_total`); the line names their kinds, never their
    * rows, and the counts say how many. */
  private class EventReport(
      source: AuditSource,
      catalog: AuditCatalog,
      window: Duration,
      counts: Ref[Map[(String, String), Int]],
      states: Ref[Set[String]],
  ):

    /** `count` events of `kind` dropped or refused for `reason`. A kind the service did not list is reported as `other`, as in the metrics, so that
      * what a request built cannot make the line long. */
    def note(reason: String, kind: AuditKind, count: Int = 1): UIO[Unit] =
      val label = if catalog.isKnown(kind) then kind.value else AuditMetrics.UnlistedKind
      counts.update(known => known.updated((reason, label), known.getOrElse((reason, label), 0) + count))

    def noteAll(reason: String, events: Iterable[AuditEvent]): UIO[Unit] =
      ZIO.foreachDiscard(events.groupBy(_.kind).toList)((kind, group) => note(reason, kind, group.size))

    /** The SQLSTATEs the database refused events with, which say why without saying what was in them. */
    def noteStates(found: Iterable[String]): UIO[Unit] = states.update(_ ++ found)

    def flush: UIO[Unit] =
      (counts.getAndSet(Map.empty) <*> states.getAndSet(Set.empty)).flatMap: (reported, sqlstates) =>
        ZIO.when(reported.nonEmpty):
          val reasons = reported.groupBy(_._1._1).toList.sortBy(_._1).map: (reason, entries) =>
            val kinds = entries.toList.map((key, n) => key._2 -> n).sortBy((kind, n) => (-n, kind))
            val shown = kinds.take(MaxKindsReported).map((kind, n) => s"$kind ($n)").mkString(", ")
            val more = if kinds.size > MaxKindsReported then s" and ${kinds.size - MaxKindsReported} more kinds" else ""
            val why = if reason == EventReport.Refused && sqlstates.nonEmpty then s"; SQLSTATE ${sqlstates.toList.sorted.mkString(", ")}" else ""
            s"${entries.values.sum} $reason: $shown$more$why"
          ZIO.logWarning(s"audit events were dropped or refused in the last ${window.render} (${source.value}): ${reasons.mkString("; ")}")
        .unit

    /** A report every window, and a last one when the service stops, after the writer's last drain. */
    val run: URIO[Scope, Unit] =
      ZIO.addFinalizer(flush) *> (ZIO.sleep(window) *> flush).forever.forkScoped.unit

  private object EventReport:
    val QueueFull = "dropped, the queue was full"
    val WaitedForRoom = "dropped, the queue stayed full for the block timeout"
    val Refused = "refused by the database for what they contain, not stored"
    val NotWritten = "not written before the service stopped"
    val AfterStop = "dropped, offered after the service began to stop"

    def make(source: AuditSource, catalog: AuditCatalog, window: Duration): UIO[EventReport] =
      for
        counts <- Ref.make(Map.empty[(String, String), Int])
        states <- Ref.make(Set.empty[String])
      yield EventReport(source, catalog, window, counts, states)

  /** The most kinds one line names for one reason; the rest are counted as "more". */
  private val MaxKindsReported = 10

  /** The SQLSTATEs anywhere in the chain of causes and chained exceptions. */
  private[audit] def sqlStates(error: Throwable): List[String] =
    def chain(e: Throwable): List[Throwable] =
      if e == null then Nil
      else
        val next = e match
          case sql: java.sql.SQLException => Option(sql.getNextException).toList.flatMap(chain)
          case _ => Nil
        e :: chain(e.getCause) ::: next
    chain(error).collect { case sql: java.sql.SQLException if sql.getSQLState != null => sql.getSQLState }

  /** Classes of SQLSTATE that say the database, not the row, is the problem: a connection that failed (08), no resources (53),
    * an operator intervention or a shutdown (57, 58), a transaction rolled back (40), an object not in a state to be used (55), an
    * internal error (XX). */
  private val infrastructureClasses = List("08", "53", "55", "57", "58", "40", "XX")

  /** Whether a row that failed on its own is one the table can never store, and so is dropped and not tried again. Always when the
    * database says it is the value (class 22, a data exception) or a constraint (class 23); and when other rows of the same pass
    * were stored, for any other refusal the database words in a SQLSTATE that is not about itself. An error that carries no SQLSTATE
    * (a timeout, a dropped connection) is never one: it is tried again. */
  private[audit] def refusedForItself(error: Throwable, othersStored: Boolean): Boolean =
    val states = sqlStates(error)
    states.exists(state => state.startsWith("22") || state.startsWith("23")) ||
    (othersStored && states.nonEmpty && !states.exists(state => infrastructureClasses.exists(state.startsWith)))

  private class Maintenance(repository: AuditEventRepository, settings: Settings, sizing: Sizing, source: AuditSource):

    private val once: IO[Unit, Unit] =
      Clock.instant.flatMap: now =>
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val limit = now.minusSeconds(settings.retention.toSeconds)
        val keepFrom = LocalDate.ofInstant(limit, ZoneOffset.UTC)
        // The gauge is read after every run, a failed one included: it is what says how long the writer can still go on.
        ownsTable.flatMap:
          case false => daysAhead(today)
          case true =>
            repository.maintainPartitions(from = today, days = sizing.partitionsAhead + 1, keepFrom = keepFrom).foldCauseZIO(
              cause => ZIO.logWarningCause("could not maintain the audit event partitions", cause) *> daysAhead(today) *> ZIO.fail(()),
              report =>
                AuditMetrics.inDefault(source, report.daysLeftInDefault.size) *>
                  ZIO.when(report.daysLeftInDefault.nonEmpty)(
                    ZIO.logWarning(
                      s"audit events of ${report.daysLeftInDefault.mkString(", ")} sit in audit_event_default, since the days had no partition when " +
                        "they were written; they are never moved, and the days get no partition until the rows are past the retention",
                    ),
                  ) *> daysAhead(today),
            ) *> prune(limit)

    /** The default partition is never dropped, so its rows past the retention are deleted here. A prune that fails is the next
      * run's; it does not fail the run, whose partitions are made. */
    private def prune(limit: Instant): UIO[Unit] =
      repository.pruneDefault(limit).foldCauseZIO(
        cause => ZIO.logWarningCause("could not delete the audit events past their retention from the default partition", cause),
        _ => ZIO.unit,
      )

    /** Whether the service may alter the table: without it every create and drop fails, so none is tried and the operator is told
      * once per run what to run. A check that itself fails (the database is down) says nothing, and the run goes on and reports
      * its own failure. */
    private val ownsTable: UIO[Boolean] =
      repository.ownership.foldZIO(
        cause => ZIO.logDebugCause("could not read the owner of the audit event table", Cause.fail(cause)).as(true),
        ownership =>
          if ownership.mayAlter then AuditMetrics.partitionOwnerMismatch(source, false).as(true)
          else
            AuditMetrics.partitionOwnerMismatch(source, true) *>
              ZIO.logError(
                s"the audit event partitions are not maintained: the table audit_event is owned by ${ownership.owner} and the service " +
                  s"connects as ${ownership.role}, which is neither that role nor a member of it. Run as ${ownership.owner} or a superuser: " +
                  s"ALTER TABLE audit_event OWNER TO ${quoted(ownership.role)}, and the same for audit_event_default. Until then no partition " +
                  s"is created or dropped, and the events of a day without a partition are written to audit_event_default. Checked again at " +
                  s"the next run, in ${sizing.maintenanceInterval.render}, or at a restart",
              ).as(false),
      )

    private def daysAhead(today: LocalDate): UIO[Unit] =
      repository.partitionDaysAhead(today).flatMap(AuditMetrics.partitionsAhead(source, _)).catchAllCause(cause =>
        ZIO.logDebugCause("could not count the audit partitions ahead", cause),
      )

    private val plainIdentifier = "[a-z_][a-z0-9_]*".r

    /** A role name as an identifier in a statement the operator copies: bare when it is plain, double-quoted otherwise. */
    private def quoted(name: String): String =
      if plainIdentifier.matches(name) then name else "\"" + name.replace("\"", "\"\"") + "\""

    /** Once before the writer starts, so its first batch finds a partition, then every interval. A run that failed (a lock that
      * could not be had in time, a database that was down) is tried again after a minute, since the writer's rows go to the
      * default partition once the days ahead are used up. */
    val run: URIO[Scope, Unit] =
      val untilDone = once.retry(Schedule.spaced(RetryInterval)).ignore
      once.either.flatMap: first =>
        (ZIO.when(first.isLeft)(ZIO.sleep(RetryInterval) *> untilDone) *> (ZIO.sleep(sizing.maintenanceInterval) *> untilDone).forever)
          .forkScoped.unit

  val RetryInterval: Duration = 1.minute

object AuditMetrics:
  private val eventsTotal = Metric.counter("audit_events_total")
  private val droppedTotal = Metric.counter("audit_events_dropped_total")
  private val blockedTotal = Metric.counter("audit_events_blocked_total")
  private val unavailableTotal = Metric.counter("audit_events_unavailable_total")
  private val failedTotal = Metric.counter("audit_events_failed_total")
  private val rejectedTotal = Metric.counter("audit_events_rejected_total")
  private val queueSizeGauge = Metric.gauge("audit_queue_size")
  private val partitionsAheadGauge = Metric.gauge("audit_partitions_ahead")
  private val inDefaultGauge = Metric.gauge("audit_events_in_default")
  private val ownerMismatchGauge = Metric.gauge("audit_partition_owner_mismatch")

  private def sourceLabel(source: AuditSource) = Set(MetricLabel("source", source.value))

  /** The counters of dropped, refused and failed events, at 0 from the start: a counter that does not exist until its first increment
    * is a series that appears with a value, and a rate over it does not see that first step. */
  def register(source: AuditSource): UIO[Unit] =
    ZIO.foreachDiscard(List(droppedTotal, blockedTotal, unavailableTotal, failedTotal, rejectedTotal))(_.tagged(sourceLabel(source)).incrementBy(0L))

  /** An event taken by the queue, by kind and outcome: both closed sets. */
  def recorded(source: AuditSource, event: AuditEvent, catalog: AuditCatalog): UIO[Unit] =
    // The kind is a label only if the service listed it: a kind built from a request would make the label unbounded.
    val kind = if catalog.isKnown(event.kind) then event.kind.value else UnlistedKind
    eventsTotal.tagged(sourceLabel(source) + MetricLabel("kind", kind) + MetricLabel("outcome", event.outcome.toString)).increment

  /** The `kind` label of an event whose kind its service did not list. */
  val UnlistedKind = "other"

  /** An event dropped at the queue: it was full under `drop`, stayed full for `blockTimeout` under a best-effort record, or was offered
    * after the service began to stop. */
  def dropped(source: AuditSource): UIO[Unit] = droppedTotal.tagged(sourceLabel(source)).increment

  /** A request that found the queue full and waited for room. Alert on it: the log is slower than the service. */
  def blocked(source: AuditSource): UIO[Unit] = blockedTotal.tagged(sourceLabel(source)).increment

  /** A request that was refused because the queue stayed full for `blockTimeout`. */
  def unavailable(source: AuditSource): UIO[Unit] = unavailableTotal.tagged(sourceLabel(source)).increment

  /** Events the writer had taken from the queue and could not write before the service stopped. */
  def failed(source: AuditSource, count: Int): UIO[Unit] = failedTotal.tagged(sourceLabel(source)).incrementBy(count.toLong)

  /** Events the database refused one by one for what they contain, after the batch they were in was refused; they are not retried. */
  def rejected(source: AuditSource, count: Int): UIO[Unit] = rejectedTotal.tagged(sourceLabel(source)).incrementBy(count.toLong)

  def queueSize(source: AuditSource, queue: Queue[?]): UIO[Unit] =
    // A queue that a taker is waiting on reports a negative size: an empty queue is 0.
    queue.size.flatMap(size => queueSizeGauge.tagged(sourceLabel(source)).set(math.max(size, 0).toDouble))

  /** Days after today that have a partition, as of the last maintenance run. Alert on it falling. */
  def partitionsAhead(source: AuditSource, days: Int): UIO[Unit] = partitionsAheadGauge.tagged(sourceLabel(source)).set(days.toDouble)

  /** Days whose rows sit in the default partition, as of the last run: a day ran without a partition. */
  def inDefault(source: AuditSource, days: Int): UIO[Unit] = inDefaultGauge.tagged(sourceLabel(source)).set(days.toDouble)

  /** 1 while the service's role may not alter the table: no partition can be created or dropped. The log line says what to run. */
  def partitionOwnerMismatch(source: AuditSource, mismatch: Boolean): UIO[Unit] =
    ownerMismatchGauge.tagged(sourceLabel(source)).set(if mismatch then 1.0 else 0.0)
