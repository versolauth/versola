package versola.antifraud

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PhoneRegion}
import versola.oauth.conversation.model.AuthId
import versola.util.http.Observability
import versola.util.http.Observability.AntifraudDetails
import versola.util.net.{AsnResolver, GeoResolver}
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

  /** Whether a code may be sent to `phone`: [[SmsBudget.Decision.Refused]] when the budget of its range is used up or the
    * range is not converting (see [[SmsBudget]]). With `charge` the code is counted as sent at once (and is to be given
    * back with [[releaseSms]] if it is not sent after all); without it the answer alone is given, for a request that
    * sends nothing and must be answered exactly like one that does. In `shadow` mode a closed range is answered
    * [[SmsBudget.Decision.WouldRefuse]], and the code goes out. Always allowed while no budget is configured. */
  def allowSms(clientId: ClientId, phone: Phone, at: Instant, charge: Boolean): UIO[SmsBudget.Decision]

  /** Gives back a code that [[allowSms]] charged at `at` and that was not sent. */
  def releaseSms(clientId: ClientId, phone: Phone, at: Instant): UIO[Unit]

  /** Forgets this instance's SMS budget of the ranges `prefix` is in or contains (digits), or of all ranges for `None`
    * (a reset: see [[AntifraudCounters]]). */
  def resetBudget(prefix: Option[String]): UIO[Unit]

  /** A real code took `took` to hand to the provider; what a fake send imitates. */
  def sendTook(took: Duration): UIO[Unit]

  /** How long a fake send waits so that it is not told from a real one by its timing: a sample of the recent real
    * ones, and `decision.fake-send-delay` until one has been timed. Zero while the decision engine is off. */
  def fakeSendDelay: UIO[Duration]

  /** Decides what to do with an OTP request for `credential` (see [[Antifraud]]); the request's IP is the one the
    * request's log context carries. Never fails; always [[Decision.allow]] while the decision engine is off.
    * A `repeated` request is one that was challenged and is made again with its answer: it is judged, but not counted
    * a second time. What is counted here is the request's source (address, device, ASN, range); the destination is
    * counted by [[otpRequested]] when a code is really sent, so that asking for codes to somebody else's number, and
    * being challenged or faked for it, never raises the count that is held against that number's owner. */
  def decideOtpRequest(
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      repeated: Boolean,
  ): UIO[Decision]

  /** An OTP was requested for `credential` and this is how it ended. */
  def otpRequested(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
      decision: Option[Decision] = None,
  ): UIO[Unit]

  /** A step of putting `decision`'s challenge of `kind` and strength `level` to the client: it was `challenged`, then
    * `challenge_passed` or `challenge_failed`. Goes on the request's log line with the decision that asked for it. */
  def challengeStep(
      authId: AuthId,
      clientId: ClientId,
      userAgent: Option[String],
      credential: Either[Email, Phone],
      outcome: AntifraudOutcome,
      decision: Decision,
      kind: String,
      level: Option[Int],
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

  /** What a deployment sets (the `antifraud` block of the config).
    *
    * @param retention      how long the event log is kept, to the minute (see [[Retention]]); it bounds how long personal data is held
    * @param statsInterval  how often `af_dest_stats` is rolled up from the log
    * @param statsRetention how long a minute of statistics is kept
    * @param smsBudget      the size of a range of numbers the SMS budget counts (see [[SmsBudget]])
    * @param decision       the rule engine that decides what to do with an OTP request, off by default
    * @param velocity       the velocity counters, off by default (see [[VelocityCounters]])
    */
  case class Settings(
      retention: Duration,
      statsInterval: Duration,
      statsRetention: Duration,
      smsBudget: SmsBudget.Settings,
      decision: Antifraud.Settings,
      velocity: VelocityCounters.Settings,
  )

  /** What is not configuration: the sizes and the pace of the writer.
    *
    * @param queueCapacity   events held while the writer catches up; one more is dropped and counted
    * @param batchSize       the most events written in one round trip
    * @param flushInterval   the writer rests this long after a batch that did not fill, which lets a trickle gather
    *                        into one batch; a full batch is followed at once by the next
    * @param partitionsAhead days of partitions created beyond today, so a maintenance run that is late does not
    *                        leave the writer without a partition
    * @param statsLookback   how far back each rollup recomputes, so that events written late are still counted
    * @param statsPrefixLength digits of a number, country code included, that make a range in `af_dest_stats`
    * @param smsBudget       the size of the SMS budget
    */
  case class Sizing(
      queueCapacity: Int,
      batchSize: Int,
      flushInterval: Duration,
      partitionsAhead: Int,
      statsLookback: Duration,
      statsPrefixLength: Int,
      smsBudget: SmsBudget.Sizing,
  )

  object Sizing:
    val standard: Sizing = Sizing(
      queueCapacity = 20000,
      batchSize = 1000,
      flushInterval = 1.second,
      partitionsAhead = 7,
      statsLookback = 2.minutes,
      statsPrefixLength = 4,
      smsBudget = SmsBudget.Sizing.standard,
    )

  /** Records nothing. For tests of what calls it, and for a deployment that does not want the log. */
  object noop extends AntifraudEvents:
    override def record(event: AntifraudEvent): UIO[Unit] = ZIO.unit

    override def subjectHash(credential: Either[Email, Phone]): Task[String] = ZIO.succeed(credential.fold(identity[String], identity[String]))

    override def allowSms(clientId: ClientId, phone: Phone, at: Instant, charge: Boolean): UIO[SmsBudget.Decision] =
      ZIO.succeed(SmsBudget.Decision.Allowed)

    override def sendTook(took: Duration): UIO[Unit] = ZIO.unit

    override def fakeSendDelay: UIO[Duration] = ZIO.succeed(Duration.Zero)

    override def releaseSms(clientId: ClientId, phone: Phone, at: Instant): UIO[Unit] = ZIO.unit

    override def resetBudget(prefix: Option[String]): UIO[Unit] = ZIO.unit

    override def decideOtpRequest(clientId: ClientId, userAgent: Option[String], credential: Either[Email, Phone], repeated: Boolean): UIO[Decision] =
      ZIO.succeed(Decision.allow)

    override def otpRequested(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
        decision: Option[Decision],
    ): UIO[Unit] = ZIO.unit

    override def challengeStep(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
        decision: Decision,
        kind: String,
        level: Option[Int],
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
    AntifraudEventRepository & Settings & Antifraud & AntifraudTuning & VelocityCounters & SecurityService & SecureRandom & CoreConfig & OAuthConfigurationService & AsnResolver & GeoResolver & Scope,
    Nothing,
    AntifraudEvents,
  ] =
    liveWith(Sizing.standard)

  def liveWith(sizing: Sizing): ZLayer[
    AntifraudEventRepository & Settings & Antifraud & AntifraudTuning & VelocityCounters & SecurityService & SecureRandom & CoreConfig & OAuthConfigurationService & AsnResolver & GeoResolver & Scope,
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
        asn <- ZIO.service[AsnResolver]
        geo <- ZIO.service[GeoResolver]
        queue <- Queue.dropping[AntifraudEvent](sizing.queueCapacity)
        // The key of the destinations' hash, run through a fixed label so that what an event stores of a number
        // cannot be matched against a hash made anywhere else with the same secret. A config without a secret of
        // its own for it (one written before it existed) falls back on the sessions secret.
        rootKey = config.security.antifraudSubjectSecret.getOrElse(config.security.sessionsSecret)
        subjectKey <- securityService.mac(Secret("versola/antifraud/subject/v1".getBytes(StandardCharsets.UTF_8)), rootKey).map(Secret(_)).orDie
        budget = new SmsBudget(settings.smsBudget, sizing.smsBudget, () => tuning.current.smsLimits)
        events = Impl(
          queue,
          sizing.queueCapacity,
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
          asn,
          geo,
        )
        _ <- (ZIO.sleep(10.minutes) *> Clock.instant.map(budget.evict)).forever.forkScoped
        _ <- Rollup(repository, settings, sizing).run
        _ <- Maintenance(repository, settings, sizing).run
        _ <- Retention(repository, settings).run
        _ <- Writer(queue, repository, sizing).run
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
      asn: AsnResolver,
      geo: GeoResolver,
  ) extends AntifraudEvents:

    override def allowSms(clientId: ClientId, phone: Phone, at: Instant, charge: Boolean): UIO[SmsBudget.Decision] =
      if !tuning.current.smsLimits.enabled then ZIO.succeed(SmsBudget.Decision.Allowed)
      else
        for
          client <- configService.find(clientId)
          regions <- configService.getPhoneRegions(clientId)
          // Where anyone may register with the number, any number is sent a real code, so a range whose codes are
          // not used says nothing about its users and everything about whoever asks; the conversion is only looked
          // at where registration is closed for the number.
          breaker = !PhoneRegion.allowsRegistration(regions, phone)
          mode = tuning.current.mode
          verdict = budget.assess(
            client.map(_.tenantId),
            phone.filter(_.isDigit),
            at,
            charge,
            conversionBreaker = breaker,
            enforce = mode == TuningMode.enforce,
          )
          _ <- ZIO.succeed(budget.drainEvicted()).flatMap(n => AntifraudMetrics.smsBudgetEvicted(n).when(n > 0))
          _ <- verdict.decision match
            case SmsBudget.Decision.Refused | SmsBudget.Decision.WouldRefuse =>
              AntifraudMetrics.smsRefused(verdict.reason.fold("cap")(_.label), mode)
            case SmsBudget.Decision.Untracked => AntifraudMetrics.smsBudgetFull
            case SmsBudget.Decision.Allowed => ZIO.unit
        yield verdict.decision

    override def decideOtpRequest(
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        repeated: Boolean,
    ): UIO[Decision] =
      if !settings.decision.enabled then ZIO.succeed(Decision.allow)
      else
        (for
          client <- configService.find(clientId)
          hash <- subjectHash(credential)
          ip <- Observability.currentIp
          // The one lookup of the request: from the database in memory, never a call outside the process.
          network <- ip.fold(ZIO.none)(asn.resolve)
          // From the country database alone: the country an AS is registered in is not where its subscribers are.
          country <- ip.fold(ZIO.none)(geo.resolve)
          // A cache read, like the client's: what `country_outside_regions` compares the country with.
          regions <- configService.getPhoneRegions(clientId)
          context = AntifraudContext(
            tenantId = client.map(_.tenantId),
            clientId = clientId,
            action = AntifraudAction.otp_request,
            subjectHash = Some(hash),
            destPrefix = credential.toOption.map(destPrefix),
            deviceId = None,
            ip = ip,
            asn = network.map(_.info.asn),
            // Whole: a `ua_hash` entry is the hash of the whole header, and a cut one would never match it.
            userAgent = userAgent,
            ja4 = None,
            signals =
              credential.left.toOption.map(email => AntifraudLists.EmailDomainSignal -> emailDomain(email)).toMap ++
                network.map(found => AsnRules.ClassSignal -> found.asnClass.toString) ++
                country.map(GeoRules.CountrySignal -> _) ++
                // From the country calling code of the number, which says nothing of an email address and, for a number
                // whose region cannot be told, nothing either.
                credential.toOption.flatMap(Phone.regionCode).map(GeoRules.PhoneCountrySignal -> _) ++
                GeoRules.regionsSignal(regions.map(_.region)).map(GeoRules.TenantRegionsSignal -> _),
          )
          // Decided on the counts with this request in them, and counted only if it is let through. The number itself
          // is not counted here but when a code is sent to it (`otpRequested`): a request that is refused, challenged
          // or faked sends nothing, and counting it would let anyone raise the count held against the number's owner.
          counted <- velocity.snapshot(context)
          decision <- antifraud.evaluate(counted)
          _ <- ZIO.unless(decision.action == DecisionAction.Block || repeated)(velocity.record(context.copy(subjectHash = None)))
          _ <- AntifraudMetrics.decided(decision) *> AntifraudMetrics.rulesFired(decision.firedRules)
          // The check goes on the `receive-http` line of the request, not into a line of its own.
          _ <- Observability.updateAntifraud(details(AntifraudAction.otp_request, credential))(
            _.copy(
              decision = decision.action.toString,
              wouldAction = Some(decision.wouldAction.toString),
              score = decision.score,
              rules = decision.firedRules,
              asn = network.map(_.info.asn),
              asnClass = network.map(_.asnClass.toString),
              country = country,
            ),
          )
        yield decision).catchAllCause: cause =>
          val failed = Antifraud.failed(settings.decision, tuning, Antifraud.Failure.Error)
          ZIO.logDebugCause("could not build the antifraud context", cause) *>
            AntifraudMetrics.evaluationFailed(Antifraud.Failure.Error.reason) *> AntifraudMetrics.decided(failed).as(failed)

    private def details(action: AntifraudAction, credential: Either[Email, Phone]): AntifraudDetails =
      AntifraudDetails(
        action = action.toString,
        decision = DecisionAction.Allow.toString,
        score = 0,
        rules = Nil,
        destPrefix = credential.toOption.map(destPrefix),
        outcome = None,
        challenge = None,
        challengeLevel = None,
        asn = None,
        asnClass = None,
        country = None,
      )

    override def resetBudget(prefix: Option[String]): UIO[Unit] = ZIO.succeed(budget.forget(prefix))

    override def sendTook(took: Duration): UIO[Unit] = ZIO.succeed(latency.add(took))

    override def fakeSendDelay: UIO[Duration] =
      if !settings.decision.enabled then ZIO.succeed(Duration.Zero)
      else Random.nextIntBounded(Int.MaxValue).map(pick => latency.sample(pick).getOrElse(settings.decision.fakeSendDelay))

    override def releaseSms(clientId: ClientId, phone: Phone, at: Instant): UIO[Unit] =
      ZIO.when(tuning.current.smsLimits.enabled):
        configService.find(clientId).map(client => budget.release(client.map(_.tenantId), phone.filter(_.isDigit), at))
      .unit

    override def record(event: AntifraudEvent): UIO[Unit] =
      feedBudget(event) *> enqueue(event)

    // Sends are counted when they are allowed (allowSms); a verification can only be seen here.
    private def feedBudget(event: AntifraudEvent): UIO[Unit] =
      ZIO.foreachDiscard(event.destPrefix.filter(_ => event.channel == AntifraudChannel.sms)): prefix =>
        event.outcome match
          // A passkey that passed in place of a sent code converts it as a verified code does: a range whose codes are passed by passkey is not one
          // whose codes nobody enters.
          case AntifraudOutcome.verified | AntifraudOutcome.passkey_alternative => ZIO.succeed(budget.verified(event.tenantId, prefix, event.at))
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
      ZIO.when(outcome == AntifraudOutcome.sent)(countSent(clientId, credential)) *>
        event(authId, clientId, userAgent, credential, AntifraudAction.otp_request, outcome, decision)

    /** The one count of the destination: a code sent to it. Its other keys were counted when the request was decided. */
    private def countSent(clientId: ClientId, credential: Either[Email, Phone]): UIO[Unit] =
      (for
        client <- configService.find(clientId)
        hash <- subjectHash(credential)
        _ <- velocity.record(
          AntifraudContext(
            tenantId = client.map(_.tenantId),
            clientId = clientId,
            action = AntifraudAction.otp_request,
            subjectHash = Some(hash),
            destPrefix = None,
            deviceId = None,
            ip = None,
            asn = None,
            userAgent = None,
            ja4 = None,
            signals = Map.empty,
          ),
        )
      yield ()).catchAllCause(cause => ZIO.logDebugCause("could not count a sent code", cause))

    override def challengeStep(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        outcome: AntifraudOutcome,
        decision: Decision,
        kind: String,
        level: Option[Int],
    ): UIO[Unit] =
      AntifraudMetrics.challenge(kind, outcome) *>
        Observability.updateAntifraud(details(AntifraudAction.otp_request, credential))(
          _.copy(
            decision = decision.action.toString,
            wouldAction = Some(decision.wouldAction.toString),
            score = decision.score,
            rules = decision.firedRules,
            challenge = Some(kind),
            challengeLevel = level,
          ),
        ) *>
        event(authId, clientId, userAgent, credential, AntifraudAction.otp_request, outcome, Some(decision))

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

    /** What follows the last `@`, as the `email_domain` list entries are stored (lower case). */
    private def emailDomain(email: Email): String = email.substring(email.lastIndexOf('@') + 1).trim.toLowerCase

    private def event(
        authId: AuthId,
        clientId: ClientId,
        userAgent: Option[String],
        credential: Either[Email, Phone],
        action: AntifraudAction,
        outcome: AntifraudOutcome,
        decision: Option[Decision] = None,
    ): UIO[Unit] =
      for
        // Looked up before (by `decideOtpRequest`, which counted it); here it is only read again, from memory.
        network <- Observability.currentIp.flatMap(_.fold(ZIO.none)(asn.peek))
        country <- Observability.currentIp.flatMap(_.fold(ZIO.none)(geo.peek))
        // How the step ended goes on the `receive-http` line of the request too, with the network it came from.
        _ <- Observability.updateAntifraud(details(action, credential))(d =>
          d.copy(
            outcome = Some(outcome.toString),
            asn = network.map(_.info.asn).orElse(d.asn),
            asnClass = network.map(_.asnClass.toString).orElse(d.asnClass),
            country = country.orElse(d.country),
          ),
        )
        // Whatever goes wrong while describing the step is the log's problem and not the sign-in's.
        _ <- (for
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
              userAgent = userAgent,
              decision = decision.map(_.action.toString),
              score = decision.map(_.score),
              rules = decision.fold(List.empty[String])(_.firedRules),
              wouldAction = decision.map(_.wouldAction.toString),
              asn = network.map(_.info.asn),
              asnClass = network.map(_.asnClass.toString),
              country = country,
            ),
          )
        yield ()).catchAllCause(cause =>
          AntifraudMetrics.dropped("build_failed") *> ZIO.logDebugCause("could not describe an antifraud event", cause),
        )
      yield ()

  /** How often the rows past the retention are deleted. */
  val PruneInterval: Duration = 5.minutes

  /** The leading digits of a phone number, country code included, an event keeps. Statistics group by a shorter
    * one with `left()`; this is the longest they could want. */
  val destPrefixLength = 6

  /** Outcomes of requests that were turned away, at the pace the sender sets: they give way first when the queue
    * fills up. */
  private val shedFirst: Set[AntifraudOutcome] = Set(AntifraudOutcome.throttled, AntifraudOutcome.locked)

  private class Writer(queue: Queue[AntifraudEvent], repository: AntifraudEventRepository, sizing: Sizing):

    /** Takes what has gathered, writes it, rests, and again. A failed write drops that batch and the loop goes on:
      * the next batch may well succeed. It rests after a batch that did not fill, and after a failed one, so a
      * database that is down is tried once per interval and not as fast as the queue can be emptied. */
    private def loop(failing: Ref[Boolean]): UIO[Nothing] =
      (for
        first <- queue.take
        rest <- queue.takeUpTo(sizing.batchSize - 1)
        batch = (first +: rest).toList
        _ <- AntifraudMetrics.queueSize(queue)
        ok <- write(batch, failing).onInterrupt(AntifraudMetrics.dropped("shutdown", batch.size))
        // A backlog (which is what an attack makes) is written back to back, so the write rate is not capped at
        // one batch per interval when it matters.
        _ <- ZIO.sleep(sizing.flushInterval).when(!ok || batch.size < sizing.batchSize)
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

  /** Why `statsInterval` is not usable, if it is: the rollup works in whole minutes (`af_dest_stats` has a row per minute and
    * a run covers the minutes that are complete), and the instances share the work by the interval, so a fraction of a
    * minute would make them run it for the same minutes over and over. Checked when the configuration is loaded. */
  def statsIntervalError(interval: Duration): Option[String] =
    Option.when(interval.toMillis < 60000L || interval.toMillis % 60000L != 0L)(
      s"antifraud.stats-interval must be a whole number of minutes, at least one (it is $interval)",
    )

  private class Rollup(repository: AntifraudEventRepository, settings: Settings, sizing: Sizing):

    // Each run recomputes at least two intervals, so that a minute is rolled up twice whatever the interval is, and
    // never less than the lookback, which covers events the writer was late with.
    private val lookback: Duration =
      if settings.statsInterval.multipliedBy(2).compareTo(sizing.statsLookback) > 0 then settings.statsInterval.multipliedBy(2)
      else sizing.statsLookback

    private val once: UIO[Unit] =
      Clock.instant.flatMap: now =>
        val to = now.truncatedTo(ChronoUnit.MINUTES)
        val from = to.minusSeconds(lookback.toSeconds)
        // One instance rolls up per interval (the repository skips the run when another has done it lately), and the one
        // that did also prunes: the others have nothing to do.
        (repository.rollupStats(from, to, sizing.statsPrefixLength, settings.statsInterval).flatMap: ran =>
          ZIO.when(ran)(repository.pruneStats(now.minusSeconds(settings.statsRetention.toSeconds)))).foldCauseZIO(
          cause => AntifraudMetrics.rollupFailed *> ZIO.logWarningCause("could not roll up the antifraud statistics", cause),
          _ => ZIO.unit,
        )

    val run: URIO[Scope, Unit] =
      (ZIO.sleep(settings.statsInterval) *> once).forever.forkScoped.unit

  private class Maintenance(repository: AntifraudEventRepository, settings: Settings, sizing: Sizing):

    private val once: IO[Unit, Unit] =
      Clock.instant.flatMap: now =>
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val keepFrom = LocalDate.ofInstant(now.minusSeconds(settings.retention.toSeconds), ZoneOffset.UTC)
        // The gauge is read after every run, a failed one included: it is what says how long the writer can still go on.
        ownsTable.flatMap:
          case false => daysAhead(today)
          case true =>
            repository.maintainPartitions(today, sizing.partitionsAhead + 1, keepFrom).foldCauseZIO(
              cause =>
                AntifraudMetrics.partitionMaintenanceFailed *>
                  ZIO.logWarningCause("could not maintain the antifraud event partitions", cause) *>
                  daysAhead(today) *>
                  ZIO.fail(()),
              moved => AntifraudMetrics.movedFromDefault(moved) *> daysAhead(today),
            )

    /** Whether the service may alter `af_event`: without it every create and drop fails, so none is tried and the operator is told
      * once per run what to run, instead of a warning each minute that says only "must be owner". A check that itself fails (the
      * database is down) says nothing, and the run goes on and reports its own failure. */
    private val ownsTable: UIO[Boolean] =
      repository.ownership.foldZIO(
        cause => ZIO.logDebugCause("could not read the owner of af_event", Cause.fail(cause)).as(true),
        ownership =>
          if ownership.mayAlter then AntifraudMetrics.partitionOwnerMismatch(false).as(true)
          else
            AntifraudMetrics.partitionOwnerMismatch(true) *>
              ZIO.logError(
                s"the antifraud event partitions are not maintained: the table af_event is owned by ${ownership.owner} and the service " +
                  s"connects as ${ownership.role}, which is neither that role nor a member of it. Run as ${ownership.owner} or a superuser: " +
                  s"ALTER TABLE af_event OWNER TO ${quoted(ownership.role)}. Until then no partition is created or dropped, and " +
                  "the events of a day without a partition are written to af_event_default (an owner change does not reach the partitions " +
                  "that exist: change the owner of af_event_default as well). Checked again at the next run, in 6 hours, or at a restart",
              ).as(false),
      )

    private def daysAhead(today: LocalDate): UIO[Unit] =
      repository.partitionDaysAhead(today).flatMap(AntifraudMetrics.partitionDaysAhead).catchAllCause(cause =>
        ZIO.logDebugCause("could not count the antifraud partitions ahead", cause),
      )

    private val plainIdentifier = "[a-z_][a-z0-9_]*".r

    /** A role name as an identifier in a statement the operator copies: bare when it is plain, double-quoted otherwise. */
    private def quoted(name: String): String =
      if plainIdentifier.matches(name) then name else "\"" + name.replace("\"", "\"\"") + "\""

    /** Once before the writer starts, so its first batch finds a partition, then every six hours. A run that failed
      * (a lock that could not be had in time, a database that was down) is tried again after a minute, not six
      * hours later, since the writer has nowhere to write once the days ahead are used up. */
    val run: URIO[Scope, Unit] =
      val untilDone = once.retry(Schedule.spaced(1.minute)).ignore
      once.either.flatMap: first =>
        (ZIO.when(first.isLeft)(ZIO.sleep(1.minute) *> untilDone) *> (ZIO.sleep(6.hours) *> untilDone).forever).forkScoped.unit

/** Keeps the event log for `retention`, to the minute. Dropping a day's partition (see [[Maintenance]]) can only happen once the whole
  * day is older than that, which keeps up to a day more than was said; the rows of the day the limit falls in that are older
  * than it are deleted here, in small pieces, since the limit moves a few minutes at a time. */
private class Retention(repository: AntifraudEventRepository, settings: AntifraudEvents.Settings):
  private val once: UIO[Unit] =
    Clock.instant.flatMap(now => repository.pruneEvents(now.minusSeconds(settings.retention.toSeconds)))
      .catchAllCause(cause => ZIO.logWarningCause("could not delete the antifraud events past their retention", cause))

  val run: URIO[Scope, Unit] =
    (ZIO.sleep(AntifraudEvents.PruneInterval) *> once).forever.forkScoped.unit

object AntifraudMetrics:
  private val enqueuedTotal = Metric.counter("antifraud_events_enqueued_total")
  private val writtenTotal = Metric.counter("antifraud_events_written_total")
  private val droppedTotal = Metric.counter("antifraud_events_dropped_total")
  private val partitionMaintenanceFailedTotal = Metric.counter("antifraud_partition_maintenance_failed_total")
  private val queueSizeGauge = Metric.gauge("antifraud_event_queue_size")

  val enqueued: UIO[Unit] = enqueuedTotal.increment

  private val tuningRefreshFailedTotal = Metric.counter("antifraud_tuning_refresh_failed_total")
  private val tuningRefreshedGauge = Metric.gauge("antifraud_tuning_refreshed_timestamp_seconds")

  /** The tuning was read (or found unchanged) at `at`. A gauge of a time, not a counter, so that a refresh that hangs, which
    * moves no failure counter, shows as the age of the last success. */
  def tuningRefreshed(at: Instant): UIO[Unit] = tuningRefreshedGauge.set(at.getEpochSecond.toDouble)

  /** The stored tuning could not be read; the instance goes on with the one it has (fail-open). Alert on it increasing. */
  val tuningRefreshFailed: UIO[Unit] = tuningRefreshFailedTotal.increment

  private val smsRefusedTotal = Metric.counter("antifraud_sms_budget_refused_total")

  private val smsBudgetFullTotal = Metric.counter("antifraud_sms_budget_full_total")

  /** A range was closed to a code: `reason` is `cap` or `conversion`, and `mode` the tuning's, since in `shadow` the code
    * went out all the same. No label for the range: the ranges are whatever an attacker's numbers make them. */
  def smsRefused(reason: String, mode: TuningMode): UIO[Unit] =
    smsRefusedTotal.tagged(Set(MetricLabel("reason", reason), MetricLabel("mode", mode.label))).increment

  private val smsBudgetEvictedTotal = Metric.counter("antifraud_sms_budget_evicted_total")

  /** Ranges that made room for a new one in a full budget table, having had the fewest codes of a sample of eight. */
  def smsBudgetEvicted(count: Long): UIO[Unit] = smsBudgetEvictedTotal.incrementBy(count)

  /** A code to a new range went out untracked because the budget already follows `max-ranges` ranges and could make no room. */
  val smsBudgetFull: UIO[Unit] = smsBudgetFullTotal.increment

  private val decisionsTotal = Metric.counter("antifraud_decisions_total")

  /** Decisions by the action taken and the one that was decided (they differ in `shadow`), a closed set of labels. */
  def decided(decision: Decision): UIO[Unit] =
    decisionsTotal.tagged(Set(MetricLabel("action", decision.action.toString), MetricLabel("would_action", decision.wouldAction.toString))).increment

  private val rulesFiredTotal = Metric.counter("antifraud_rules_fired_total")

  /** The rules a decision fired, by rule name (a rule of the catalogue, [[Antifraud.ruleNames]]) and whether it was one that only
    * records (`shadow:<name>` in the decision). The stand-ins of a failed evaluation are not rules and are not counted. */
  def rulesFired(names: List[String]): UIO[Unit] =
    ZIO.foreachDiscard(names.filterNot(Antifraud.failureRules.contains)): name =>
      val shadow = name.startsWith(Antifraud.ShadowPrefix)
      rulesFiredTotal.tagged(Set(MetricLabel("rule", name.stripPrefix(Antifraud.ShadowPrefix)), MetricLabel("shadow", shadow.toString))).increment

  private val evaluationFailedTotal = Metric.counter("antifraud_evaluation_failed_total")

  /** An evaluation gave no decision and `failure-action` applied: `reason` is `timeout`, `error` or `tuning_unavailable`
    * ([[Antifraud.Failure]]). */
  def evaluationFailed(reason: String): UIO[Unit] =
    evaluationFailedTotal.tagged(Set(MetricLabel("reason", reason))).increment

  private val challengesTotal = Metric.counter("antifraud_challenges_total")

  /** Steps of putting a challenge to a client by `kind` (a verifier's, a closed set) and how each ended. */
  def challenge(kind: String, outcome: AntifraudOutcome): UIO[Unit] =
    challengesTotal.tagged(Set(MetricLabel("kind", kind), MetricLabel("outcome", outcome.toString))).increment

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

  /** Requests of keys that were not counted because the table of keys was full and no key could be picked to make room
    * (a race): more than 0 is a sign of a table too small. */
  def velocityUntracked(count: Long): UIO[Unit] = velocityUntrackedTotal.incrementBy(count)

  private val velocityEvictedTotal = Metric.counter("antifraud_velocity_evicted_total")

  /** Keys that made room for a new one in a full table, having had the fewest requests of a random sample of eight in the
    * last ten minutes. A steady stream is a flood of keys. */
  def velocityEvicted(count: Long): UIO[Unit] = velocityEvictedTotal.incrementBy(count)

  def velocityKeys(count: Int): UIO[Unit] = velocityKeysGauge.set(count.toDouble)

  val partitionMaintenanceFailed: UIO[Unit] = partitionMaintenanceFailedTotal.increment

  private val partitionOwnerMismatchGauge = Metric.gauge("antifraud_partition_owner_mismatch")

  /** 1 while the service's role is not the owner of `af_event` (nor a member of its role): no partition can be created or
    * dropped, as of the last run. Alert on it being 1; the log line says what to run. */
  def partitionOwnerMismatch(mismatch: Boolean): UIO[Unit] = partitionOwnerMismatchGauge.set(if mismatch then 1.0 else 0.0)

  private val eventsMovedFromDefaultTotal = Metric.counter("antifraud_events_moved_from_default_total")

  /** Events that were written to the default partition, since their day had no partition, and moved into it once it had. More than
    * 0 means a day ran without a partition: the maintenance was late, or could not run. */
  def movedFromDefault(count: Long): UIO[Unit] = ZIO.when(count > 0)(eventsMovedFromDefaultTotal.incrementBy(count)).unit

  private val partitionDaysAheadGauge = Metric.gauge("antifraud_partition_days_ahead")

  /** Days after today that have a partition of the event log, as of the last maintenance run. The writer drops events
    * when it reaches 0 (and `partitions-ahead` is 7), so alert on it falling, not only on the failures. */
  def partitionDaysAhead(days: Int): UIO[Unit] = partitionDaysAheadGauge.set(days.toDouble)

  def written(count: Int): UIO[Unit] = writtenTotal.incrementBy(count.toLong)

  /** `reason` is one of `queue_full`, `throttled_shed`, `write_failed`, `build_failed`, `shutdown`: a closed set, since it is a label. */
  def dropped(reason: String, count: Int = 1): UIO[Unit] =
    droppedTotal.tagged(Set(MetricLabel("reason", reason))).incrementBy(count.toLong)

  def queueSize(queue: Queue[?]): UIO[Unit] =
    queue.size.flatMap(size => queueSizeGauge.set(size.toDouble))
