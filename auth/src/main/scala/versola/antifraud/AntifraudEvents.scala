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
    * @param flushInterval  the writer rests this long between batches, which bounds the writes per second at
    *                       `1 / flushInterval` whatever the traffic, and lets a burst gather into one batch
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
        // A key of its own for the destinations, derived from one the deployment already holds: what an event
        // stores of a number then cannot be matched against a hash made anywhere else with that secret.
        subjectKey <- securityService.mac(Secret("versola/antifraud/subject/v1".getBytes(StandardCharsets.UTF_8)), config.security.sessionsSecret).orDie
        events = Impl(queue, securityService, secureRandom, configService, subjectKey)
        _ <- Maintenance(repository, settings).run
        _ <- Writer(queue, repository, settings).run
      yield events

  class Impl(
      queue: Queue[AntifraudEvent],
      securityService: SecurityService,
      secureRandom: SecureRandom,
      configService: OAuthConfigurationService,
      subjectKey: Array[Byte],
  ) extends AntifraudEvents:

    override def record(event: AntifraudEvent): UIO[Unit] =
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

  private class Writer(queue: Queue[AntifraudEvent], repository: AntifraudEventRepository, settings: Settings):

    /** Takes what has gathered, writes it, rests, and again. A failed write drops that batch, counted and logged
      * once, and the loop goes on: the next batch may well succeed. */
    private val loop: UIO[Nothing] =
      (for
        first <- queue.take
        rest <- queue.takeUpTo(settings.batchSize - 1)
        batch = (first +: rest).toList
        _ <- AntifraudMetrics.queueSize(queue)
        _ <- write(batch)
        _ <- ZIO.sleep(settings.flushInterval)
      yield ()).forever

    private def write(batch: List[AntifraudEvent]): UIO[Unit] =
      repository.insertBatch(batch).foldCauseZIO(
        cause =>
          AntifraudMetrics.dropped("write_failed", batch.size) *>
            ZIO.logWarningCause(s"dropped ${batch.size} antifraud events: they could not be written", cause),
        _ => AntifraudMetrics.written(batch.size),
      )

    /** The final write is bounded: a database that is down must not hold the shutdown up. */
    private val drain: UIO[Unit] =
      queue.takeAll.flatMap(events => ZIO.when(events.nonEmpty)(write(events.toList)).unit).timeout(5.seconds).unit

    val run: URIO[Scope, Unit] =
      loop.forkScoped.unit *> ZIO.addFinalizer(drain).unit

  private class Maintenance(repository: AntifraudEventRepository, settings: Settings):

    private val once: UIO[Unit] =
      Clock.instant.flatMap: now =>
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val keepFrom = LocalDate.ofInstant(now.minusSeconds(settings.retention.toSeconds), ZoneOffset.UTC)
        repository.maintainPartitions(today, settings.partitionsAhead + 1, keepFrom).foldCauseZIO(
          cause => ZIO.logWarningCause("could not maintain the antifraud event partitions", cause),
          _ => ZIO.unit,
        )

    /** Once before the writer starts, so its first batch finds a partition, then every six hours. */
    val run: URIO[Scope, Unit] =
      once *> (ZIO.sleep(6.hours) *> once).forever.forkScoped.unit

object AntifraudMetrics:
  private val enqueuedTotal = Metric.counter("antifraud_events_enqueued_total")
  private val writtenTotal = Metric.counter("antifraud_events_written_total")
  private val droppedTotal = Metric.counter("antifraud_events_dropped_total")
  private val queueSizeGauge = Metric.gauge("antifraud_event_queue_size")

  val enqueued: UIO[Unit] = enqueuedTotal.increment

  def written(count: Int): UIO[Unit] = writtenTotal.incrementBy(count.toLong)

  /** `reason` is one of `queue_full`, `write_failed`, `build_failed`: a closed set, since it is a label. */
  def dropped(reason: String, count: Int = 1): UIO[Unit] =
    droppedTotal.tagged(Set(MetricLabel("reason", reason))).incrementBy(count.toLong)

  def queueSize(queue: Queue[?]): UIO[Unit] =
    queue.size.flatMap(size => queueSizeGauge.set(size.toDouble))
