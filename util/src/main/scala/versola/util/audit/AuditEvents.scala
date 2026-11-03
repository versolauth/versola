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

  /** What a deployment sets (the `audit` block of the config).
    *
    * @param retention    how long the log is kept: a day's partition is dropped once the whole day is older than this
    * @param overflow     what a full queue does to the request: holds it ([[Overflow.block]]) or loses the event ([[Overflow.drop]])
    * @param blockTimeout how long a request waits for room in the queue under `block` before it fails
    * @param events       the kinds a deployment may leave out, since they are many and tell little ([[EventTiers]])
    */
  case class Settings(retention: Duration, overflow: Overflow, blockTimeout: Duration, events: EventTiers)

  enum Overflow:
    case block, drop

  /** The high-volume successes a deployment may not want: every `refresh_token` grant, every authorize and PAR request that
    * succeeded, every admin look at a user. */
  case class EventTiers(tokenRefresh: Boolean, authorizeSuccess: Boolean, parSuccess: Boolean, adminUserViewed: Boolean)

  /** What is not configuration: the sizes and the pace of the writer and the maintenance.
    *
    * @param queueCapacity       events held while the writer catches up; past it a request blocks or an event is dropped
    * @param batchSize           the most events written in one round trip
    * @param flushInterval       the writer rests this long after a batch that did not fill, and between attempts at a batch that
    *                            could not be written
    * @param partitionsAhead     days of partitions created beyond today, so a maintenance run that is late does not leave the
    *                            writer without one
    * @param maintenanceInterval how often the partitions are made ahead, the old ones dropped and the default partition pruned
    */
  case class Sizing(
      queueCapacity: Int = 50000,
      batchSize: Int = 1000,
      flushInterval: Duration = 1.second,
      partitionsAhead: Int = 14,
      maintenanceInterval: Duration = 1.hour,
  )

  object Sizing:
    val standard: Sizing = Sizing()

  /** Records nothing. For tests of what calls it, and for a service that has no log. */
  object noop extends AuditEvents:
    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = ZIO.unit

    override def recordBestEffort(draft: AuditDraft): UIO[Unit] = ZIO.unit

  /** Starts the writer and the partition maintenance; both stop with the scope, and what is still queued is written once more on
    * the way out, within a bound. `source` and `nodeId` are the service's: every event it records carries them. */
  def live(source: AuditSource, nodeId: String): ZLayer[AuditEventRepository & Settings & Tracing & SecureRandom & Scope, Nothing, AuditEvents] =
    liveWith(Sizing.standard)(source, nodeId)

  def liveWith(
      sizing: Sizing,
  )(source: AuditSource, nodeId: String): ZLayer[AuditEventRepository & Settings & Tracing & SecureRandom & Scope, Nothing, AuditEvents] =
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
        _ <- Maintenance(repository, settings, sizing, source).run
        _ <- Writer(queue, repository, sizing, source).run
      yield Impl(queue = queue, settings = settings, sizing = sizing, source = source, nodeId = nodeId, tracing = tracing, random = random)

  private class Impl(
      queue: Queue[AuditEvent],
      settings: Settings,
      sizing: Sizing,
      source: AuditSource,
      nodeId: String,
      tracing: Tracing,
      random: SecureRandom,
  ) extends AuditEvents:

    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] =
      fill(draft).flatMap(enqueue(_, bestEffort = false))

    override def recordBestEffort(draft: AuditDraft): UIO[Unit] =
      fill(draft).flatMap(enqueue(_, bestEffort = true)).catchAll(_ => ZIO.unit)

    private def enqueue(event: AuditEvent, bestEffort: Boolean): IO[AuditUnavailable.type, Unit] =
      settings.overflow match
        case Overflow.drop =>
          queue.offer(event).flatMap:
            case true => AuditMetrics.recorded(source, event)
            case false => AuditMetrics.dropped(source)
        case Overflow.block =>
          // The size is read before the offer so that a request that had to wait is counted, whether or not it got room in time.
          queue.size.flatMap: size =>
            ZIO.when(size >= sizing.queueCapacity)(AuditMetrics.blocked(source)) *>
              queue.offer(event).timeout(settings.blockTimeout).flatMap:
                case Some(_) => AuditMetrics.recorded(source, event)
                case None if bestEffort => AuditMetrics.dropped(source)
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
        AuditEvent(
          id = id,
          at = now,
          tenantId = context.tenantId,
          source = source,
          nodeId = nodeId,
          kind = draft.kind,
          outcome = draft.outcome,
          reason = draft.reason,
          severity = AuditSeverity.of(draft.kind, draft.outcome),
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
        )

  /** The member of `details` that lists the fields an update changed, from [[AuditContext.setChanged]]. */
  val ChangedField = "changed"

  private class Writer(queue: Queue[AuditEvent], repository: AuditEventRepository, sizing: Sizing, source: AuditSource):

    /** Takes what has gathered, writes it, rests, and again. A batch that could not be written is not dropped: it is tried
      * again after the interval, for as long as it takes, while the queue fills behind it and the requests block or drop by
      * the setting. A backlog is written back to back, so the write rate is not capped at one batch per interval. */
    private def loop(failing: Ref[Boolean]): UIO[Nothing] =
      (for
        first <- queue.take
        rest <- queue.takeUpTo(sizing.batchSize - 1)
        batch = (first +: rest).toList
        _ <- AuditMetrics.queueSize(source, queue)
        _ <- write(batch, failing).onInterrupt(AuditMetrics.failed(source, batch.size))
        _ <- ZIO.sleep(sizing.flushInterval).when(batch.size < sizing.batchSize)
      yield ()).forever

    /** Until the batch is written. The warning, with its stack, is logged once when writes start failing and once more when they
      * recover, not for every attempt in between. */
    private def write(batch: List[AuditEvent], failing: Ref[Boolean]): UIO[Unit] =
      repository.insertBatch(batch).foldCauseZIO(
        cause =>
          failing.getAndSet(true).flatMap(was =>
            ZIO.logWarningCause(s"could not write ${batch.size} audit events; they are kept and tried again", cause).unless(was),
          ) *> ZIO.sleep(sizing.flushInterval) *> write(batch, failing),
        _ => failing.getAndSet(false).flatMap(was => ZIO.logInfo("audit events are being written again").when(was)).unit,
      )

    /** The final write is bounded and tried once: a database that is down must not hold the shutdown up, and what it could
      * not take is counted as failed. */
    private def drain(failing: Ref[Boolean]): UIO[Unit] =
      queue.takeAll.flatMap: events =>
        ZIO.when(events.nonEmpty)(
          repository.insertBatch(events.toList).timeout(DrainTimeout).foldCauseZIO(
            cause => AuditMetrics.failed(source, events.size) *> ZIO.logWarningCause(s"${events.size} audit events were lost at shutdown", cause),
            {
              case None => AuditMetrics.failed(
                  source,
                  events.size,
                ) *> ZIO.logWarning(s"${events.size} audit events were lost at shutdown: the write did not finish in $DrainTimeout")
              case Some(_) => ZIO.unit
            },
          ),
        ).unit

    /** The loop is stopped before the last write, so that nothing is writing while it is made. */
    val run: URIO[Scope, Unit] =
      for
        failing <- Ref.make(false)
        // Not `fork`: that would tie the writer to the fiber that builds the layer, which ends once it is built.
        fiber <- loop(failing).forkDaemon
        _ <- ZIO.addFinalizer(fiber.interrupt *> drain(failing))
      yield ()

  val DrainTimeout: Duration = 5.seconds

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
  private val queueSizeGauge = Metric.gauge("audit_queue_size")
  private val partitionsAheadGauge = Metric.gauge("audit_partitions_ahead")
  private val inDefaultGauge = Metric.gauge("audit_events_in_default")
  private val ownerMismatchGauge = Metric.gauge("audit_partition_owner_mismatch")

  private def sourceLabel(source: AuditSource) = Set(MetricLabel("source", source.toString))

  /** An event taken by the queue, by kind and outcome: both closed sets. */
  def recorded(source: AuditSource, event: AuditEvent): UIO[Unit] =
    eventsTotal.tagged(sourceLabel(source) + MetricLabel("kind", event.kind.value) + MetricLabel("outcome", event.outcome.toString)).increment

  /** An event lost at the queue: it was full under `drop`, or stayed full for `blockTimeout` under a best-effort record. */
  def dropped(source: AuditSource): UIO[Unit] = droppedTotal.tagged(sourceLabel(source)).increment

  /** A request that found the queue full and waited for room. Alert on it: the log is slower than the service. */
  def blocked(source: AuditSource): UIO[Unit] = blockedTotal.tagged(sourceLabel(source)).increment

  /** A request that was refused because the queue stayed full for `blockTimeout`. */
  def unavailable(source: AuditSource): UIO[Unit] = unavailableTotal.tagged(sourceLabel(source)).increment

  /** Events the writer had taken from the queue and could not write before the service stopped. */
  def failed(source: AuditSource, count: Int): UIO[Unit] = failedTotal.tagged(sourceLabel(source)).incrementBy(count.toLong)

  def queueSize(source: AuditSource, queue: Queue[?]): UIO[Unit] =
    queue.size.flatMap(size => queueSizeGauge.tagged(sourceLabel(source)).set(size.toDouble))

  /** Days after today that have a partition, as of the last maintenance run. Alert on it falling. */
  def partitionsAhead(source: AuditSource, days: Int): UIO[Unit] = partitionsAheadGauge.tagged(sourceLabel(source)).set(days.toDouble)

  /** Days whose rows sit in the default partition, as of the last run: a day ran without a partition. */
  def inDefault(source: AuditSource, days: Int): UIO[Unit] = inDefaultGauge.tagged(sourceLabel(source)).set(days.toDouble)

  /** 1 while the service's role may not alter the table: no partition can be created or dropped. The log line says what to run. */
  def partitionOwnerMismatch(source: AuditSource, mismatch: Boolean): UIO[Unit] =
    ownerMismatchGauge.tagged(sourceLabel(source)).set(if mismatch then 1.0 else 0.0)
