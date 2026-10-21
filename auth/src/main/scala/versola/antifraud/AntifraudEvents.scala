package versola.antifraud

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.AuthId
import versola.util.{Base64Url, CoreConfig, Email, Phone, SecureRandom, Secret, SecurityService}
import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.nio.charset.StandardCharsets
import java.time.{LocalDate, ZoneOffset}

/** The antifraud event log, as the sign-in steps see it: something to tell, never something to wait for.
  *
  * `record` hands an event to a bounded in-memory queue and returns. A background writer takes what has gathered
  * and writes it to the repository in batches, so recording costs the request nothing on the hot path, and neither a
  * slow nor an unavailable database can hold a sign-in up: when the queue is full the event is dropped and counted.
  * Losing an event loses a data point, never a login.
  */
trait AntifraudEvents:
  def record(event: AntifraudEvent): UIO[Unit]

  /** An OTP was requested for `credential` and this is how it ended. */
  def otpRequested(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
  ): UIO[Unit]

  /** A submitted OTP was checked and this is how it ended. */
  def otpChecked(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
  ): UIO[Unit]

object AntifraudEvents:

  /** @param queueCapacity  events held while the writer catches up; one more is dropped and counted
    * @param batchSize      the most events written in one round trip
    * @param flushInterval  the writer rests this long after a batch that did not fill, which lets a trickle gather
    *                       into one batch; a full batch is followed at once by the next
    * @param retention      how long a day's partition is kept
    * @param partitionsAhead days of partitions created beyond today, so a maintenance run that is late does not
    *                       leave the writer without a partition
    */
  case class Settings(
      queueCapacity: Int = 20000,
      batchSize: Int = 1000,
      flushInterval: Duration = 1.second,
      retention: Duration = 14.days,
      partitionsAhead: Int = 3,
  )

  object Settings:
    val default: Settings = Settings()

  /** Records nothing. For tests of what calls it, and for a deployment that does not want the log. */
  object noop extends AntifraudEvents:
    override def record(event: AntifraudEvent): UIO[Unit] = ZIO.unit

    override def otpRequested(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
    ): UIO[Unit] = ZIO.unit

    override def otpChecked(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
    ): UIO[Unit] = ZIO.unit

  /** Starts the writer and the partition maintenance; both stop with the scope, and what is still queued is written
    * once more on the way out, within a bound. */
  val live: ZLayer[
    AntifraudEventRepository & Settings & SecurityService & SecureRandom & CoreConfig & OAuthConfigurationService & Scope,
    Nothing,
    AntifraudEvents,
  ] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudEventRepository]
        settings <- ZIO.service[Settings]
        securityService <- ZIO.service[SecurityService]
        secureRandom <- ZIO.service[SecureRandom]
        config <- ZIO.service[CoreConfig]
        configService <- ZIO.service[OAuthConfigurationService]
        queue <- Queue.dropping[AntifraudEvent](settings.queueCapacity)
        // The key of the destinations' hash, run through a fixed label so that what an event stores of a number
        // cannot be matched against a hash made anywhere else with the same secret. A config without a secret of
        // its own for it (one written before it existed) falls back on the sessions secret.
        rootKey = config.security.antifraudSubjectSecret.getOrElse(config.security.sessionsSecret)
        subjectKey <- securityService.mac(Secret("versola/antifraud/subject/v1".getBytes(StandardCharsets.UTF_8)), rootKey).map(Secret(_)).orDie
        events = Impl(queue, settings.queueCapacity, securityService, secureRandom, configService, subjectKey)
        _ <- Maintenance(repository, settings).run
        _ <- Writer(queue, repository, settings).run
      yield events

  class Impl(
      queue: Queue[AntifraudEvent],
      queueCapacity: Int,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      configService: OAuthConfigurationService,
      subjectKey: Secret,
  ) extends AntifraudEvents:

    override def record(event: AntifraudEvent): UIO[Unit] =
      // A refused request (`throttled`, `locked`) is logged at the pace its sender sets, so once the queue is half full they give way: what
      // is left is for the requests that were let through, which the statistics are made of.
      val shed = AntifraudEvents.shedFirst.contains(event.outcome)
      queue.size.flatMap: size =>
        if shed && size > queueCapacity / 2 then AntifraudMetrics.dropped("throttled_shed")
        else
          queue.offer(event).flatMap:
            case true => AntifraudMetrics.enqueued
            case false => AntifraudMetrics.dropped("queue_full")

    override def otpRequested(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
    ): UIO[Unit] =
      event(authId, clientId, userAgent, credential, AntifraudAction.otp_request, outcome)

    override def otpChecked(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
    ): UIO[Unit] =
      event(authId, clientId, userAgent, credential, AntifraudAction.otp_verify, outcome)

    private def event(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        action: AntifraudAction,
        outcome: AntifraudOutcome,
    ): UIO[Unit] =
      // Whatever goes wrong while describing the step is the log's problem and not the sign-in's.
      (for
        id <- secureRandom.nextUUIDv7
        now <- Clock.instant
        client <- configService.find(clientId)
        subject = credential.fold(_.toLowerCase, identity[String])
        hash <- securityService.mac(Secret(subject.getBytes(StandardCharsets.UTF_8)), subjectKey)
        _ <- record(
          AntifraudEvent(
            id = id,
            at = now,
            tenantId = client.map(_.tenantId),
            clientId = clientId,
            conversationId = authId,
            action = action,
            outcome = outcome,
            channel = credential.fold(_ => AntifraudChannel.email, _ => AntifraudChannel.sms),
            subjectHash = Base64Url.encode(hash),
            destPrefix = credential.toOption.map(phone => phone.filter(_.isDigit).take(AntifraudEvents.destPrefixLength)),
            userAgent = userAgent.map(_.take(AntifraudEvents.userAgentLength)),
          ),
        )
      yield ()).catchAllCause(cause => AntifraudMetrics.dropped("build_failed") *> ZIO.logDebugCause("could not describe an antifraud event", cause))

  /** The leading digits of a phone number, country code included, an event keeps. Statistics group by a shorter
    * one with `left()`; this is the longest they could want. */
  val destPrefixLength = 6

  val userAgentLength = 256

  /** Outcomes of requests that were turned away, at the pace the sender sets: they give way first when the queue
    * fills up. */
  private val shedFirst: Set[AntifraudOutcome] = Set(AntifraudOutcome.throttled, AntifraudOutcome.locked)

  private class Writer(queue: Queue[AntifraudEvent], repository: AntifraudEventRepository, settings: Settings):

    /** Takes what has gathered, writes it, rests, and again. A failed write drops that batch and the loop goes on:
      * the next batch may well succeed. It rests after a batch that did not fill, and after a failed one, so a
      * database that is down is tried once per interval and not as fast as the queue can be emptied. */
    private def loop(failing: Ref[Boolean]): UIO[Nothing] =
      (for
        first <- queue.take
        rest <- queue.takeUpTo(settings.batchSize - 1)
        batch = (first +: rest).toList
        _ <- AntifraudMetrics.queueSize(queue)
        ok <- write(batch, failing).onInterrupt(AntifraudMetrics.dropped("shutdown", batch.size))
        // A backlog (which is what an attack makes) is written back to back, so the write rate is not capped at
        // one batch per interval when it matters.
        _ <- ZIO.sleep(settings.flushInterval).when(!ok || batch.size < settings.batchSize)
      yield ()).forever

    /** Whether the batch was written. The warning, with its stack, is logged once when writes start failing and
      * once more when they recover, not for every batch in between. */
    private def write(batch: List[AntifraudEvent], failing: Ref[Boolean]): UIO[Boolean] =
      repository.insertBatch(batch).foldCauseZIO(
        cause =>
          AntifraudMetrics.dropped("write_failed", batch.size) *>
            failing.getAndSet(true).flatMap(was =>
              ZIO.logWarningCause(s"dropped ${batch.size} antifraud events: they could not be written", cause).unless(was),
            ).as(false),
        _ =>
          AntifraudMetrics.written(batch.size) *>
            failing.getAndSet(false).flatMap(was => ZIO.logInfo("antifraud events are being written again").when(was)).as(true),
      )

    /** The final write is bounded: a database that is down must not hold the shutdown up. */
    private def drain(failing: Ref[Boolean]): UIO[Unit] =
      queue.takeAll.flatMap: events =>
        ZIO.when(events.nonEmpty)(
          write(events.toList, failing).timeout(5.seconds).flatMap:
            case None => AntifraudMetrics.dropped("shutdown", events.size)
            case Some(_) => ZIO.unit,
        ).unit

    /** The loop is stopped before the last write, so that nothing is writing while it is made; the batch it was
      * in the middle of is counted as dropped. */
    val run: URIO[Scope, Unit] =
      for
        failing <- Ref.make(false)
        fiber <- loop(failing).fork
        _ <- ZIO.addFinalizer(fiber.interrupt *> drain(failing))
      yield ()

  private class Maintenance(repository: AntifraudEventRepository, settings: Settings):

    private val once: IO[Unit, Unit] =
      Clock.instant.flatMap: now =>
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val keepFrom = LocalDate.ofInstant(now.minusSeconds(settings.retention.toSeconds), ZoneOffset.UTC)
        repository.maintainPartitions(today, settings.partitionsAhead + 1, keepFrom).foldCauseZIO(
          cause =>
            AntifraudMetrics.partitionMaintenanceFailed *>
              ZIO.logWarningCause("could not maintain the antifraud event partitions", cause) *>
              ZIO.fail(()),
          _ => ZIO.unit,
        )

    /** Once before the writer starts, so its first batch finds a partition, then every six hours. A run that failed
      * (a lock that could not be had in time, a database that was down) is tried again after a minute, not six
      * hours later, since the writer has nowhere to write once the days ahead are used up. */
    val run: URIO[Scope, Unit] =
      val untilDone = once.retry(Schedule.spaced(1.minute)).ignore
      once.either.flatMap: first =>
        (ZIO.when(first.isLeft)(ZIO.sleep(1.minute) *> untilDone) *> (ZIO.sleep(6.hours) *> untilDone).forever).forkScoped.unit

object AntifraudMetrics:
  private val enqueuedTotal = Metric.counter("antifraud_events_enqueued_total")
  private val writtenTotal = Metric.counter("antifraud_events_written_total")
  private val droppedTotal = Metric.counter("antifraud_events_dropped_total")
  private val partitionMaintenanceFailedTotal = Metric.counter("antifraud_partition_maintenance_failed_total")
  private val queueSizeGauge = Metric.gauge("antifraud_event_queue_size")

  val enqueued: UIO[Unit] = enqueuedTotal.increment

  val partitionMaintenanceFailed: UIO[Unit] = partitionMaintenanceFailedTotal.increment

  def written(count: Int): UIO[Unit] = writtenTotal.incrementBy(count.toLong)

  /** `reason` is one of `queue_full`, `throttled_shed`, `write_failed`, `build_failed`, `shutdown`: a closed set, since it is a label. */
  def dropped(reason: String, count: Int = 1): UIO[Unit] =
    droppedTotal.tagged(Set(MetricLabel("reason", reason))).incrementBy(count.toLong)

  def queueSize(queue: Queue[?]): UIO[Unit] =
    queue.size.flatMap(size => queueSizeGauge.set(size.toDouble))
