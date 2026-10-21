package versola.antifraud

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.model.AuthId
import versola.util.{Email, Phone, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
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

  private def build(e: Env, settings: AntifraudEvents.Settings): ZIO[Scope, Nothing, AntifraudEvents] =
    (for
      _ <- e.repository.maintainPartitions.succeedsWith(())
      events <- AntifraudEvents.live.build.map(_.get[AntifraudEvents])
    yield events).provideSome[Scope](
      ZLayer.succeed[AntifraudEventRepository](e.repository),
      ZLayer.succeed(settings),
      ZLayer.succeed(TestEnvConfig.coreConfig),
      ZLayer.succeed[OAuthConfigurationService](e.configService),
      SecureRandom.live,
      SecureRandom.live >>> SecurityService.live,
    )

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
      test("a failing run is logged and does not stop the writer") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.maintainPartitions.failsWith(RuntimeException("no permission"))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- AntifraudEvents.live.build.map(_.get[AntifraudEvents]).provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              SecureRandom.live,
              SecureRandom.live >>> SecurityService.live,
            )
            _ <- events.record(event())
            _ <- TestClock.adjust(0.seconds)
          yield assertTrue(e.repository.insertBatch.calls.size == 1)
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
