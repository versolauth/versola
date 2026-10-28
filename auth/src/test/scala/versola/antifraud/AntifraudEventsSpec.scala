package versola.antifraud

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PhoneRegion, TenantId}
import versola.oauth.conversation.model.AuthId
import versola.util.http.Observability
import versola.util.{Email, Phone, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.logging.logContext
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object AntifraudEventsSpec extends UnitSpecBase:

  private val clientId = ClientId("client-a")
  private val authId = AuthId(UUID.fromString("018f2a4c-0000-7000-8000-000000000001"))
  private val phone = Phone("+4915123456789")

  private def event(outcome: AntifraudOutcome = AntifraudOutcome.sent, at: Instant = Instant.EPOCH) = AntifraudEvent(
    id = UUID.randomUUID(),
    at = at,
    tenantId = None,
    clientId = clientId,
    conversationId = UUID.randomUUID(),
    action = AntifraudAction.otp_request,
    outcome = outcome,
    channel = AntifraudChannel.sms,
    subjectHash = "h",
    destPrefix = None,
    userAgent = None,
  )

  private case class Env(repository: Stub[AntifraudEventRepository], configService: Stub[OAuthConfigurationService])

  private def env: Env = Env(stub[AntifraudEventRepository], stub[OAuthConfigurationService])

  private def build(
      e: Env,
      settings: AntifraudEvents.Settings,
      config: versola.util.CoreConfig = TestEnvConfig.coreConfig,
      antifraud: Antifraud = Antifraud.off,
      velocity: VelocityCounters = VelocityCounters.off,
  ): ZIO[Scope, Nothing, AntifraudEvents] =
    (for
      _ <- e.repository.maintainPartitions.succeedsWith(())
      _ <- e.repository.rollupStats.succeedsWith(true)
      _ <- e.repository.pruneStats.succeedsWith(())
      events <- AntifraudEvents.live.build.map(_.get[AntifraudEvents])
    yield events).provideSome[Scope](
      ZLayer.succeed[AntifraudEventRepository](e.repository),
      ZLayer.succeed(settings),
      ZLayer.succeed(config),
      ZLayer.succeed[OAuthConfigurationService](e.configService),
      ZLayer.succeed[Antifraud](antifraud),
      ZLayer.succeed[VelocityCounters](velocity),
      SecureRandom.live,
      SecureRandom.live >>> SecurityService.live,
    )

  private val t0 = Instant.parse("2026-10-21T10:00:30Z")

  private val small = AntifraudEvents.Settings(queueCapacity = 3, batchSize = 10, flushInterval = 1.second, retention = 14.days, partitionsAhead = 3)

  def spec = suite("AntifraudEvents")(
    suite("the writer")(
      test("writes an event without the caller waiting for it, and gathers what arrives while it rests into one batch") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small)
            first = event()
            _ <- events.record(first)
            _ <- TestClock.adjust(0.seconds)
            afterFirst = e.repository.insertBatch.calls
            later = List(event(), event())
            _ <- ZIO.foreachDiscard(later)(events.record)
            _ <- TestClock.adjust(1.second)
            afterRest = e.repository.insertBatch.calls
          yield assertTrue(afterFirst == List(List(first)), afterRest == List(List(first), later))
      },
      test("drops what does not fit rather than blocking, and writes everything that did") {
        val e = env
        ZIO.scoped:
          for
            started <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            _ <- e.repository.insertBatch.returnsZIO(_ => started.succeed(()) *> release.await)
            events <- build(e, small)
            written = event()
            _ <- events.record(written)
            _ <- started.await
            // The writer holds `written`; the queue takes three more and refuses the rest.
            queued = List(event(), event(), event())
            dropped = event()
            _ <- ZIO.foreachDiscard(queued)(events.record)
            _ <- events.record(dropped)
            _ <- release.succeed(())
            _ <- TestClock.adjust(1.second)
            all = e.repository.insertBatch.calls.flatten
          yield assertTrue(all == written :: queued, !all.contains(dropped))
      },
      test("a failed write drops that batch and the writer goes on with the next") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.insertBatch.returnsZIO:
              case List(only) if only.outcome == AntifraudOutcome.throttled => ZIO.fail(RuntimeException("database is down"))
              case _ => ZIO.unit
            events <- build(e, small)
            lost = event(AntifraudOutcome.throttled)
            kept = event(AntifraudOutcome.verified)
            _ <- events.record(lost)
            _ <- TestClock.adjust(0.seconds)
            _ <- TestClock.adjust(1.second)
            _ <- events.record(kept)
            _ <- TestClock.adjust(1.second)
          yield assertTrue(e.repository.insertBatch.calls == List(List(lost), List(kept)))
      },
      test("refused requests give way once the queue is half full, and the others still get in") {
        val e = env
        ZIO.scoped:
          for
            started <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            _ <- e.repository.insertBatch.returnsZIO(_ => started.succeed(()) *> release.await)
            events <- build(e, small)
            _ <- events.record(event())
            _ <- started.await
            // capacity 3: two are queued, so a refused request would make the queue more than half full.
            early = List(event(AntifraudOutcome.throttled), event(AntifraudOutcome.sent))
            _ <- ZIO.foreachDiscard(early)(events.record)
            shed = event(AntifraudOutcome.throttled)
            kept = event(AntifraudOutcome.verified)
            _ <- events.record(shed)
            _ <- events.record(kept)
            _ <- release.succeed(())
            _ <- TestClock.adjust(1.second)
            all = e.repository.insertBatch.calls.flatten
          yield assertTrue(!all.contains(shed), all.contains(kept), all.containsSlice(early))
      },
      test("a backlog is written batch after batch without resting in between") {
        val e = env
        ZIO.scoped:
          for
            started <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            _ <- e.repository.insertBatch.returnsZIO:
              case List(_) => started.succeed(()) *> release.await
              case _ => ZIO.unit
            events <- build(e, small.copy(queueCapacity = 10, batchSize = 2))
            _ <- events.record(event())
            _ <- started.await
            _ <- ZIO.foreachDiscard(List.fill(4)(event()))(events.record)
            _ <- release.succeed(())
            // One rest after the first (short) batch, then both full ones in the same instant.
            _ <- TestClock.adjust(1.second)
          yield assertTrue(e.repository.insertBatch.calls.map(_.size) == List(1, 2, 2))
      },
      test("a database that is down is tried about once an interval, however full the queue, and its failure is logged once") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.insertBatch.failsWith(RuntimeException("database is down"))
            events <- build(e, small.copy(queueCapacity = 100, batchSize = 2))
            _ <- ZIO.foreachDiscard(List.fill(50)(event()))(events.record)
            _ <- TestClock.adjust(1.second)
            calls <- ZIO.succeed(e.repository.insertBatch.calls.size)
            warnings <- ZTestLogger.logOutput.map(_.count(_.logLevel == LogLevel.Warning))
          yield assertTrue(calls <= 3, warnings <= 1)
      },
      test("locked requests give way like refused ones") {
        val e = env
        ZIO.scoped:
          for
            started <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            _ <- e.repository.insertBatch.returnsZIO(_ => started.succeed(()) *> release.await)
            events <- build(e, small)
            _ <- events.record(event())
            _ <- started.await
            _ <- ZIO.foreachDiscard(List(event(), event()))(events.record)
            shed = event(AntifraudOutcome.locked)
            _ <- events.record(shed)
            _ <- release.succeed(())
            _ <- TestClock.adjust(1.second)
          yield assertTrue(!e.repository.insertBatch.calls.flatten.contains(shed))
      },
      test("keeps writing after the fiber that built it has ended") {
        val e = env
        for
          scope <- Scope.make
          _ <- e.repository.maintainPartitions.succeedsWith(())
          _ <- e.repository.insertBatch.succeedsWith(())
          _ <- e.repository.rollupStats.succeedsWith(true)
          _ <- e.repository.pruneStats.succeedsWith(())
          events <- AntifraudEvents.live.build.map(_.get[AntifraudEvents]).provideSome[Scope](
            ZLayer.succeed[AntifraudEventRepository](e.repository),
            ZLayer.succeed(small),
            ZLayer.succeed(TestEnvConfig.coreConfig),
            ZLayer.succeed[OAuthConfigurationService](e.configService),
            ZLayer.succeed[Antifraud](Antifraud.off),
            ZLayer.succeed[VelocityCounters](VelocityCounters.off),
            SecureRandom.live,
            SecureRandom.live >>> SecurityService.live,
          ).provideEnvironment(ZEnvironment(scope)).fork.flatMap(_.join)
          _ <- events.record(event())
          _ <- TestClock.adjust(0.seconds)
          _ <- scope.close(Exit.unit)
        yield assertTrue(e.repository.insertBatch.calls.nonEmpty)
      },
      test("writes what is still queued when it stops") {
        val e = env
        for
          _ <- e.repository.insertBatch.succeedsWith(())
          pending = List(event(), event())
          _ <- ZIO.scoped:
            for
              events <- build(e, small)
              _ <- events.record(event())
              _ <- TestClock.adjust(0.seconds)
              _ <- ZIO.foreachDiscard(pending)(events.record)
            yield ()
        yield assertTrue(e.repository.insertBatch.calls.last == pending)
      },
    ),
    suite("partition maintenance")(
      test("runs before anything is written, ahead of today, and again every six hours") {
        val e = env
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:00Z"))
            _ <- e.repository.maintainPartitions.succeedsWith(())
            _ <- build(e, small)
            first = e.repository.maintainPartitions.calls
            _ <- TestClock.adjust(6.hours)
            second = e.repository.maintainPartitions.calls
          yield assertTrue(
            first.take(1) == List((LocalDate.of(2026, 10, 21), 4, LocalDate.of(2026, 10, 7))),
            second.size >= 2,
          )
      },
      test("a failed run is tried again after a minute, not six hours later") {
        val e = env
        ZIO.scoped:
          for
            attempts <- Ref.make(0)
            _ <- e.repository.maintainPartitions.returnsZIO: _ =>
              attempts.updateAndGet(_ + 1).flatMap(n => ZIO.fail(RuntimeException("lock timeout")).when(n == 1).unit)
            _ <- e.repository.insertBatch.succeedsWith(())
            _ <- e.repository.rollupStats.succeedsWith(true)
            _ <- e.repository.pruneStats.succeedsWith(())
            _ <- AntifraudEvents.live.build.provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              ZLayer.succeed[Antifraud](Antifraud.off),
              ZLayer.succeed[VelocityCounters](VelocityCounters.off),
              SecureRandom.live,
              SecureRandom.live >>> SecurityService.live,
            )
            _ <- TestClock.adjust(0.seconds)
            beforeRetry <- attempts.get
            _ <- TestClock.adjust(1.minute)
            afterRetry <- attempts.get
            _ <- TestClock.adjust(5.minutes)
            later <- attempts.get
          yield assertTrue(beforeRetry == 1, afterRetry == 2, later == 2)
      },
      test("a failing run is logged and does not stop the writer") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.maintainPartitions.failsWith(RuntimeException("no permission"))
            _ <- e.repository.insertBatch.succeedsWith(())
            _ <- e.repository.rollupStats.succeedsWith(true)
            _ <- e.repository.pruneStats.succeedsWith(())
            events <- AntifraudEvents.live.build.map(_.get[AntifraudEvents]).provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              ZLayer.succeed[Antifraud](Antifraud.off),
              ZLayer.succeed[VelocityCounters](VelocityCounters.off),
              SecureRandom.live,
              SecureRandom.live >>> SecurityService.live,
            )
            _ <- events.record(event())
            _ <- TestClock.adjust(0.seconds)
          yield assertTrue(e.repository.insertBatch.calls.size == 1)
      },
    ),
    suite("the SMS budget")(
      test("charged sends close the range, a check that charges nothing does not count, and another range is still allowed") {
        val e = env
        val limited = small.copy(smsBudget = SmsBudget.Settings(sentPerHour = 2))
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, limited)
            _ <- ZIO.foreachDiscard(1 to 5)(_ => events.allowSms(clientId, phone, t0, charge = false))
            first <- events.allowSms(clientId, phone, t0, charge = true)
            second <- events.allowSms(clientId, Phone("+4915100000000"), t0, charge = true)
            third <- events.allowSms(clientId, Phone("+4915199999999"), t0, charge = true)
            readOnly <- events.allowSms(clientId, phone, t0, charge = false)
            otherRange <- events.allowSms(clientId, Phone("+4917112345678"), t0, charge = true)
            _ <- events.releaseSms(clientId, phone, t0)
            afterRelease <- events.allowSms(clientId, phone, t0, charge = false)
          yield assertTrue(first, second, !third, !readOnly, otherRange, afterRelease)
      },
      test("the conversion is only looked at where registration is closed for the number; the hourly limit applies everywhere") {
        val germany = PhoneRegion(region = "DE", dialCode = "+49", length = 11, regex = ".*", registration = true)
        val closedGermany = germany.copy(registration = false)
        def run(regions: List[PhoneRegion], settings: SmsBudget.Settings) =
          val e = env
          ZIO.scoped:
            for
              _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
              _ <- e.configService.getPhoneRegions.succeedsWith(regions)
              _ <- e.repository.insertBatch.succeedsWith(())
              events <- build(e, small.copy(smsBudget = settings))
              _ <- ZIO.foreachDiscard(1 to 5)(_ => events.allowSms(clientId, phone, t0, charge = true))
              allowed <- events.allowSms(clientId, phone, t0, charge = false)
            yield allowed
        val breaker = SmsBudget.Settings(minVolume = 5, minConversion = 0.5)
        for
          open <- run(List(germany), breaker)
          closed <- run(List(closedGermany), breaker)
          limitOpen <- run(List(germany), SmsBudget.Settings(sentPerHour = 5))
        yield assertTrue(open, !closed, !limitOpen)
      },
      test("with no budget configured nothing is looked up") {
        val e = env
        ZIO.scoped:
          for
            events <- build(e, small)
            allowed <- events.allowSms(clientId, phone, t0, charge = true)
          yield assertTrue(allowed, e.configService.find.calls.isEmpty)
      },
    ),
    suite("the statistics rollup")(
      test("recomputes the last minutes every interval, and removes what is older than the retention") {
        val e = env
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:30Z"))
            _ <- build(e, small.copy(statsRetention = 30.days))
            _ <- TestClock.adjust(1.minute)
            rolled = e.repository.rollupStats.calls
            pruned = e.repository.pruneStats.calls
          yield assertTrue(
            rolled == List((Instant.parse("2026-10-21T09:59:00Z"), Instant.parse("2026-10-21T10:01:00Z"), 4)),
            pruned == List(Instant.parse("2026-09-21T10:01:30Z")),
          )
      },
    ),
    suite("the request's log")(
      test("a decision, the rules that fired and the outcome are put on the request's own log context, with its IP") {
        val e = env
        val seen = new java.util.concurrent.atomic.AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] =
            ZIO.succeed(seen.set(Some(ctx))) *> ZIO.succeed(Decision(DecisionAction.Block, 12, List("deny_list")))
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = Antifraud.Settings(enabled = true)), antifraud = engine)
            _ <- Observability.setIp("203.0.113.7")
            decision <- events.decideOtpRequest(clientId, Some("Mozilla/5.0"), Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.blocked, Some(decision))
            details <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(
            seen.get.flatMap(_.ip) == Some("203.0.113.7"),
            seen.get.flatMap(_.destPrefix) == Some("491512"),
            details == Some(
              Observability.AntifraudDetails("otp_request", "Block", 12, List("deny_list"), Some("491512"), Some("blocked"), None, None),
            ),
          )
      },
      test("with the engine off no decision is made and the outcome alone is put on the log context") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small)
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            details <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(
            decision == Decision.allow,
            details == Some(Observability.AntifraudDetails("otp_request", "Allow", 0, Nil, Some("491512"), Some("sent"), None, None)),
          )
      },
      test("a challenge step puts the decision, the kind and the level of the challenge on the log context, and its outcome in the event log") {
        val e = env
        val challenging = Decision(DecisionAction.Challenge, 61, List("phone_velocity"))
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small)
            _ <- events.challengeStep(authId, clientId, None, Right(phone), AntifraudOutcome.challenged, challenging, "pow", Some(17))
            details <- logContext.get.map(_.get(Observability.antifraud))
            _ <- TestClock.adjust(0.seconds)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            details == Some(Observability.AntifraudDetails(
              "otp_request",
              "Challenge",
              61,
              List("phone_velocity"),
              Some("491512"),
              Some("challenged"),
              Some("pow"),
              Some(17),
            )),
            written.map(_.outcome) == List(AntifraudOutcome.challenged),
            written.map(_.action) == List(AntifraudAction.otp_request),
            written.flatMap(_.decision) == List("Challenge"),
          )
      },
    ),
    suite("velocity in the decision")(
      test("an allowed request is counted, and the limit fires on the one over it") {
        val e = env
        val thresholds = VelocityRules.Thresholds(phone = VelocityRules.Limit(perMinute = 2, weight = 60))
        val vsettings = VelocityCounters.Settings(enabled = true, thresholds = thresholds)
        val velocity = VelocityCounters(vsettings)
        val engine = Antifraud.Impl(
          Antifraud.Settings(
            enabled = true,
            blockAt = 50,
            delayAt = 0,
            challengeAt = 0,
            reviewAt = 0,
            delay = 1.second,
            timeout = 1.second,
            failureAction = DecisionAction.Challenge,
          ),
          List(VelocityRules.ruleSet(vsettings)),
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <-
              build(e, small.copy(decision = Antifraud.Settings(enabled = true), velocity = vsettings), antifraud = engine, velocity = velocity)
            first <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            second <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            third <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            fourth <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            first.action == DecisionAction.Allow,
            second.action == DecisionAction.Allow,
            third.action == DecisionAction.Block,
            third.firedRules == List("velocity_phone_1m"),
            fourth.action == DecisionAction.Block,
          )
      },
      test("a request made again with its answer is judged but not counted a second time") {
        val e = env
        val vsettings =
          VelocityCounters.Settings(enabled = true, thresholds = VelocityRules.Thresholds(phone = VelocityRules.Limit(perMinute = 2, weight = 60)))
        val velocity = VelocityCounters(vsettings)
        val engine = Antifraud.Impl(
          Antifraud.Settings(
            enabled = true,
            blockAt = 50,
            delayAt = 0,
            challengeAt = 0,
            reviewAt = 0,
            delay = 1.second,
            timeout = 1.second,
            failureAction = DecisionAction.Challenge,
          ),
          List(VelocityRules.ruleSet(vsettings)),
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <-
              build(e, small.copy(decision = Antifraud.Settings(enabled = true), velocity = vsettings), antifraud = engine, velocity = velocity)
            first <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            again <- ZIO.foreach(1 to 3)(_ => events.decideOtpRequest(clientId, None, Right(phone), repeated = true))
            second <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            third <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            first.action == DecisionAction.Allow,
            again.forall(_.action == DecisionAction.Allow),
            second.action == DecisionAction.Allow,
            third.action == DecisionAction.Block,
          )
      },
    ),
    suite("describing a step")(
      test("keeps a keyed hash of the number and its leading digits, never the number") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small)
            _ <- events.otpRequested(authId, clientId, Some("Mozilla/5.0"), Right(phone), AntifraudOutcome.sent)
            _ <- TestClock.adjust(0.seconds)
            written = e.repository.insertBatch.calls.flatten.head
          yield assertTrue(
            written.action == AntifraudAction.otp_request,
            written.outcome == AntifraudOutcome.sent,
            written.channel == AntifraudChannel.sms,
            written.conversationId == authId,
            written.clientId == clientId,
            written.tenantId.isDefined,
            written.destPrefix == Some("491512"),
            written.userAgent == Some("Mozilla/5.0"),
            !written.subjectHash.contains("4915123456789"),
            written.subjectHash.nonEmpty,
          )
      },
      test("the same destination always hashes alike, and a different one does not") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(None)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(queueCapacity = 10))
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.verified)
            _ <- events.otpRequested(authId, clientId, None, Right(Phone("+4915123456780")), AntifraudOutcome.sent)
            _ <- TestClock.adjust(0.seconds)
            _ <- TestClock.adjust(1.second)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            written.size == 3,
            written(0).subjectHash == written(1).subjectHash,
            written(0).subjectHash != written(2).subjectHash,
            written(1).action == AntifraudAction.otp_verify,
            written(0).tenantId.isEmpty,
          )
      },
      test("a secret of its own keys the hash; without one the sessions secret does, so a config that predates it still works") {
        val own = TestEnvConfig.coreConfig.copy(
          security = TestEnvConfig.coreConfig.security.copy(antifraudSubjectSecret = Some(versola.util.Secret.Bytes32(Array.fill(32)(7.toByte)))),
        )
        def hashOf(config: versola.util.CoreConfig) =
          val e = env
          ZIO.scoped:
            for
              _ <- e.configService.find.succeedsWith(None)
              _ <- e.repository.insertBatch.succeedsWith(())
              events <- build(e, small, config)
              _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
              _ <- TestClock.adjust(0.seconds)
            yield e.repository.insertBatch.calls.flatten.head.subjectHash
        for
          fallback <- hashOf(TestEnvConfig.coreConfig)
          fallbackAgain <- hashOf(TestEnvConfig.coreConfig)
          withOwn <- hashOf(own)
        yield assertTrue(fallback == fallbackAgain, fallback != withOwn)
      },
      test("an address has no prefix, is hashed case-insensitively, and the user agent is cut to a length") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(None)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(queueCapacity = 10))
            long = "x" * 1000
            _ <- events.otpRequested(authId, clientId, Some(long), Left(Email("User@Example.com")), AntifraudOutcome.suppressed)
            _ <- events.otpRequested(authId, clientId, None, Left(Email("user@example.com")), AntifraudOutcome.suppressed)
            _ <- TestClock.adjust(0.seconds)
            _ <- TestClock.adjust(1.second)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            written.forall(_.channel == AntifraudChannel.email),
            written.forall(_.destPrefix.isEmpty),
            written.map(_.subjectHash).distinct.size == 1,
            written.head.userAgent.map(_.length) == Some(AntifraudEvents.userAgentLength),
          )
      },
    ),
  ) @@ TestAspect.sequential
