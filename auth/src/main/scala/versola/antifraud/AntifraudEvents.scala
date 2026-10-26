package versola.antifraud

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PhoneRegion}
import versola.oauth.conversation.model.AuthId
import versola.util.http.Observability
import versola.util.http.Observability.AntifraudDetails
import versola.util.{Base64Url, CoreConfig, Email, Phone, Secret, SecureRandom, SecurityService}
import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.nio.charset.StandardCharsets
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}

/** The antifraud event log, as the sign-in steps see it: something to tell, never something to wait for.
  *
  * `record` hands an event to a bounded in-memory queue and returns. A background writer takes what has gathered
  * and writes it to the repository in batches, so recording costs the request nothing on the hot path, and neither a
  * slow nor an unavailable database can hold a sign-in up: when the queue is full the event is dropped and counted.
  * Losing an event loses a data point, never a login.
  */
trait AntifraudEvents:
  def record(event: AntifraudEvent): UIO[Unit]

  /** The keyed hash an event stores for a destination, which a `phone_hash` list entry is matched against. */
  def subjectHash(credential: Either[Email, Phone]): Task[String]

  /** Whether a code may be sent to `phone`: false when the budget of its range is used up or the range is not
    * converting (see [[SmsBudget]]). With `charge` the code is counted as sent at once (and is to be given back
    * with [[releaseSms]] if it is not sent after all); without it the answer alone is given, for a request that
    * sends nothing and must be answered exactly like one that does. Always true while no budget is configured. */
  def allowSms(clientId: ClientId, phone: Phone, at: Instant, charge: Boolean): UIO[Boolean]

  /** Gives back a code that [[allowSms]] charged at `at` and that was not sent. */
  def releaseSms(clientId: ClientId, phone: Phone, at: Instant): UIO[Unit]

  /** Decides what to do with an OTP request for `credential` (see [[Antifraud]]); the request's IP is the one the
    * request's log context carries. Never fails; always
    * [[Decision.allow]] while the decision engine is off. */
  def decideOtpRequest(
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
  ): UIO[Decision]

  /** Holds the request back as `decision` asks (a `Delay`; a `Challenge` too, until a challenge can be asked for,
    * P1-2): the pause is the friction. Returns at once for any other decision. */
  def hold(decision: Decision): UIO[Unit]

  /** A real code took `took` to hand to the provider; what a fake send imitates. */
  def sendTook(took: Duration): UIO[Unit]

  /** How long a fake send waits so that it is not told from a real one by its timing: a sample of the recent real
    * ones. Zero while the decision engine is off or nothing was measured yet. */
  def fakeSendDelay: UIO[Duration]

  /** An OTP was requested for `credential` and this is how it ended. */
  def otpRequested(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
      decision: Option[Decision] = None,
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
    * @param smsBudget      the limit on codes sent to one range of numbers, off by default
    * @param decision       the rule engine that decides what to do with an OTP request, off by default
    * @param statsInterval  how often `af_dest_stats` is rolled up from the log
    * @param statsLookback  how far back each rollup recomputes, so that events written late are still counted
    * @param statsRetention how long a minute of statistics is kept
    * @param statsPrefixLength digits of a number, country code included, that make a range in `af_dest_stats`
    * @param velocity       the velocity counters and the limits of their rules, off by default (see [[VelocityCounters]])
    */
  case class Settings(
      queueCapacity: Int,
      batchSize: Int,
      flushInterval: Duration,
      retention: Duration,
      partitionsAhead: Int,
      smsBudget: SmsBudget.Settings,
      decision: Antifraud.Settings,
      statsInterval: Duration,
      statsLookback: Duration,
      statsRetention: Duration,
      statsPrefixLength: Int,
      velocity: VelocityCounters.Settings,
  )

  /** Records nothing. For tests of what calls it, and for a deployment that does not want the log. */
  object noop extends AntifraudEvents:
    override def record(event: AntifraudEvent): UIO[Unit] = ZIO.unit

    override def subjectHash(credential: Either[Email, Phone]): Task[String] = ZIO.succeed(credential.fold(identity[String], identity[String]))

    override def allowSms(clientId: ClientId, phone: Phone, at: Instant, charge: Boolean): UIO[Boolean] = ZIO.succeed(true)

    override def releaseSms(clientId: ClientId, phone: Phone, at: Instant): UIO[Unit] = ZIO.unit

    override def decideOtpRequest(clientId: ClientId, userAgent: Option[String], credential: Either[Email, Phone]): UIO[Decision] =
      ZIO.succeed(Decision.allow)

    override def hold(decision: Decision): UIO[Unit] = ZIO.unit

    override def sendTook(took: Duration): UIO[Unit] = ZIO.unit

    override def fakeSendDelay: UIO[Duration] = ZIO.succeed(Duration.Zero)

    override def otpRequested(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
        decision: Option[Decision],
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
    AntifraudEventRepository & Settings & Antifraud & AntifraudTuning & VelocityCounters & SecurityService & SecureRandom & CoreConfig & OAuthConfigurationService & Scope,
    Nothing,
    AntifraudEvents,
  ] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudEventRepository]
        settings <- ZIO.service[Settings]
        antifraud <- ZIO.service[Antifraud]
        velocity <- ZIO.service[VelocityCounters]
        tuning <- ZIO.service[AntifraudTuning]
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
        budget = new SmsBudget(settings.smsBudget, tenant => tuning.effective(tenant).smsLimits)
        events = Impl(
          queue,
          settings.queueCapacity,
          budget,
          antifraud,
          velocity,
          SendLatency(),
          settings,
          tuning,
          securityService,
          secureRandom,
          configService,
          subjectKey,
        )
        _ <- (ZIO.sleep(10.minutes) *> Clock.instant.map(budget.evict)).forever.forkScoped
        _ <- Rollup(repository, settings).run
        _ <- Maintenance(repository, settings).run
        _ <- Writer(queue, repository, settings).run
      yield events

  class Impl(
      queue: Queue[AntifraudEvent],
      queueCapacity: Int,
      budget: SmsBudget,
      antifraud: Antifraud,
      velocity: VelocityCounters,
      latency: SendLatency,
      settings: Settings,
      tuning: AntifraudTuning,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      configService: OAuthConfigurationService,
      subjectKey: Secret,
  ) extends AntifraudEvents:

    override def allowSms(clientId: ClientId, phone: Phone, at: Instant, charge: Boolean): UIO[Boolean] =
      if !tuning.smsLimitsEnabled then ZIO.succeed(true)
      else
        for
          client <- configService.find(clientId)
          regions <- configService.getPhoneRegions(clientId)
          // Where anyone may register with the number, any number is sent a real code, so a range whose codes are
          // not used says nothing about its users and everything about whoever asks; the conversion is only looked
          // at where registration is closed for the number.
          breaker = !PhoneRegion.allowsRegistration(regions, phone)
          decision = budget.decide(client.map(_.tenantId), phone.filter(_.isDigit), at, charge, conversionBreaker = breaker)
          _ <- decision match
            case SmsBudget.Decision.Refused => AntifraudMetrics.smsRefused
            case SmsBudget.Decision.Untracked => AntifraudMetrics.smsBudgetFull
            case SmsBudget.Decision.Allowed => ZIO.unit
        yield decision != SmsBudget.Decision.Refused

    override def decideOtpRequest(
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
    ): UIO[Decision] =
      if !settings.decision.enabled then ZIO.succeed(Decision.allow)
      else
        (for
          client <- configService.find(clientId)
          hash <- subjectHash(credential)
          ip <- Observability.currentIp
          context = AntifraudContext(
            tenantId = client.map(_.tenantId),
            clientId = clientId,
            action = AntifraudAction.otp_request,
            subjectHash = Some(hash),
            destPrefix = credential.toOption.map(destPrefix),
            deviceId = None,
            ip = ip,
            asn = None,
            userAgent = userAgent.map(_.take(AntifraudEvents.userAgentLength)),
            ja4 = None,
            signals = Map.empty,
          )
          // Decided on the counts with this request in them, and counted only if it is let through: a request that is
          // refused, or one aimed at somebody else's number, must not raise the count used against that number's owner.
          counted <- velocity.snapshot(context)
          decision <- antifraud.evaluate(counted)
          _ <- ZIO.unless(decision.action == DecisionAction.Block)(velocity.record(context))
          _ <- AntifraudMetrics.decided(decision.action)
          // The check goes on the `receive-http` line of the request, not into a line of its own.
          _ <- Observability.updateAntifraud(details(AntifraudAction.otp_request, credential))(
            _.copy(decision = decision.action.toString, score = decision.score, rules = decision.firedRules),
          )
        yield decision).catchAllCause: cause =>
          ZIO.logDebugCause("could not build the antifraud context", cause) *>
            AntifraudMetrics.decided(settings.decision.failureAction).as(Decision(settings.decision.failureAction, 0, List("antifraud_error")))

    private def details(action: AntifraudAction, credential: Either[Email, Phone]): AntifraudDetails =
      AntifraudDetails(action.toString, DecisionAction.Allow.toString, 0, Nil, credential.toOption.map(destPrefix), None)

    override def hold(decision: Decision): UIO[Unit] =
      decision.action match
        case DecisionAction.Delay | DecisionAction.Challenge => ZIO.sleep(tuning.effective(None).delay)
        case _ => ZIO.unit

    override def sendTook(took: Duration): UIO[Unit] = ZIO.succeed(latency.add(took))

    override def fakeSendDelay: UIO[Duration] =
      if !settings.decision.enabled then ZIO.succeed(Duration.Zero)
      else
        tuning.effective(None).fakeSendDelayMillis match
          case 0L => Random.nextIntBounded(Int.MaxValue).map(latency.sample)
          case millis => ZIO.succeed(Duration.fromMillis(millis))

    override def releaseSms(clientId: ClientId, phone: Phone, at: Instant): UIO[Unit] =
      ZIO.when(tuning.smsLimitsEnabled):
        configService.find(clientId).map(client => budget.release(client.map(_.tenantId), phone.filter(_.isDigit), at))
      .unit

    override def record(event: AntifraudEvent): UIO[Unit] =
      feedBudget(event) *> enqueue(event)

    // Sends are counted when they are allowed (allowSms); a verification can only be seen here.
    private def feedBudget(event: AntifraudEvent): UIO[Unit] =
      ZIO.foreachDiscard(event.destPrefix.filter(_ => event.channel == AntifraudChannel.sms)): prefix =>
        event.outcome match
          case AntifraudOutcome.verified => ZIO.succeed(budget.verified(event.tenantId, prefix, event.at))
          case _ => ZIO.unit

    private def enqueue(event: AntifraudEvent): UIO[Unit] =
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
        decision: Option[Decision],
    ): UIO[Unit] =
      event(authId, clientId, userAgent, credential, AntifraudAction.otp_request, outcome, decision)

    override def otpChecked(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
    ): UIO[Unit] =
      event(authId, clientId, userAgent, credential, AntifraudAction.otp_verify, outcome)

    override def subjectHash(credential: Either[Email, Phone]): Task[String] =
      val subject = credential.fold(_.toLowerCase, identity[String])
      securityService.mac(Secret(subject.getBytes(StandardCharsets.UTF_8)), subjectKey).map(Base64Url.encode)

    private def destPrefix(phone: Phone): String = phone.filter(_.isDigit).take(AntifraudEvents.destPrefixLength)

    private def event(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        action: AntifraudAction,
        outcome: AntifraudOutcome,
        decision: Option[Decision] = None,
    ): UIO[Unit] =
      // How the step ended goes on the `receive-http` line of the request too.
      Observability.updateAntifraud(details(action, credential))(_.copy(outcome = Some(outcome.toString))) *>
        // Whatever goes wrong while describing the step is the log's problem and not the sign-in's.
        (for
          id <- secureRandom.nextUUIDv7
          now <- Clock.instant
          client <- configService.find(clientId)
          hash <- subjectHash(credential)
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
              subjectHash = hash,
              destPrefix = credential.toOption.map(destPrefix),
              userAgent = userAgent.map(_.take(AntifraudEvents.userAgentLength)),
              decision = decision.map(_.action.toString),
              score = decision.map(_.score),
              rules = decision.fold(List.empty[String])(_.firedRules),
            ),
          )
        yield ()).catchAllCause(cause =>
          AntifraudMetrics.dropped("build_failed") *> ZIO.logDebugCause("could not describe an antifraud event", cause),
        )

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
        // Not `fork`: that would tie the writer to the fiber that builds the layer, which ends once it is built.
        fiber <- loop(failing).forkDaemon
        _ <- ZIO.addFinalizer(fiber.interrupt *> drain(failing))
      yield ()

  private class Rollup(repository: AntifraudEventRepository, settings: Settings):

    private val once: UIO[Unit] =
      Clock.instant.flatMap: now =>
        val to = now.truncatedTo(ChronoUnit.MINUTES)
        val from = to.minusSeconds(settings.statsLookback.toSeconds)
        (repository.rollupStats(from, to, settings.statsPrefixLength) *>
          repository.pruneStats(now.minusSeconds(settings.statsRetention.toSeconds))).foldCauseZIO(
          cause => AntifraudMetrics.rollupFailed *> ZIO.logWarningCause("could not roll up the antifraud statistics", cause),
          _ => ZIO.unit,
        )

    val run: URIO[Scope, Unit] =
      (ZIO.sleep(settings.statsInterval) *> once).forever.forkScoped.unit

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

  private val tuningRefreshFailedTotal = Metric.counter("antifraud_tuning_refresh_failed_total")

  /** The stored tuning could not be read; the instance goes on with the one it has (fail-open). Alert on it increasing. */
  val tuningRefreshFailed: UIO[Unit] = tuningRefreshFailedTotal.increment

  private val smsRefusedTotal = Metric.counter("antifraud_sms_budget_refused_total")

  private val smsBudgetFullTotal = Metric.counter("antifraud_sms_budget_full_total")

  /** No label for the range: the ranges are whatever an attacker's numbers make them. */
  val smsRefused: UIO[Unit] = smsRefusedTotal.increment

  /** A code to a new range went out untracked because the budget already follows `max-ranges` ranges. */
  val smsBudgetFull: UIO[Unit] = smsBudgetFullTotal.increment

  private val decisionsTotal = Metric.counter("antifraud_decisions_total")

  /** Decisions by action, a closed set of labels. */
  def decided(action: DecisionAction): UIO[Unit] =
    decisionsTotal.tagged(Set(MetricLabel("action", action.toString))).increment

  private val rollupFailedTotal = Metric.counter("antifraud_stats_rollup_failed_total")

  val rollupFailed: UIO[Unit] = rollupFailedTotal.increment

  private val velocityFlushedTotal = Metric.counter("antifraud_velocity_flushed_total")
  private val velocityFailedTotal = Metric.counter("antifraud_velocity_failed_total")
  private val velocityUntrackedTotal = Metric.counter("antifraud_velocity_untracked_total")
  private val velocityKeysGauge = Metric.gauge("antifraud_velocity_keys")

  /** Buckets this instance wrote to the database. */
  def velocityFlushed(count: Int): UIO[Unit] = velocityFlushedTotal.incrementBy(count.toLong)

  /** `what` is `flush`, `load` or `prune`: a closed set, since it is a label. A failure leaves the counts per instance. */
  def velocityFailed(what: String): UIO[Unit] = velocityFailedTotal.tagged(Set(MetricLabel("what", what))).increment

  /** Requests of keys that were not counted because the table of keys was full of keys seen more than once
    * (`velocity.max-keys`): more than 0 is itself a sign of an attack, or of a table too small. */
  def velocityUntracked(count: Long): UIO[Unit] = velocityUntrackedTotal.incrementBy(count)

  private val velocityEvictedTotal = Metric.counter("antifraud_velocity_evicted_total")

  /** Keys that made room for a new one in a full table, having been seen once. A steady stream is a flood of keys. */
  def velocityEvicted(count: Long): UIO[Unit] = velocityEvictedTotal.incrementBy(count)

  def velocityKeys(count: Int): UIO[Unit] = velocityKeysGauge.set(count.toDouble)

  val partitionMaintenanceFailed: UIO[Unit] = partitionMaintenanceFailedTotal.increment

  def written(count: Int): UIO[Unit] = writtenTotal.incrementBy(count.toLong)

  /** `reason` is one of `queue_full`, `throttled_shed`, `write_failed`, `build_failed`, `shutdown`: a closed set, since it is a label. */
  def dropped(reason: String, count: Int = 1): UIO[Unit] =
    droppedTotal.tagged(Set(MetricLabel("reason", reason))).incrementBy(count.toLong)

  def queueSize(queue: Queue[?]): UIO[Unit] =
    queue.size.flatMap(size => queueSizeGauge.set(size.toDouble))
