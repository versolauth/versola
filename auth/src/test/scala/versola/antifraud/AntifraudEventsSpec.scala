package versola.antifraud

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PhoneRegion, TenantId}
import versola.oauth.conversation.model.AuthId
import versola.util.http.Observability
import versola.util.net.{
  AsnClassifier,
  AsnDatabase,
  AsnDatabaseLoader,
  AsnResolver,
  DbIpCountryFormat,
  GeoResolver,
  RangeDatabase,
  RangeDatabaseLoader,
}
import versola.util.partitions.TableOwnership
import versola.util.{Email, Phone, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.json.*
import zio.logging.logContext
import zio.metrics.{Metric, MetricLabel, MetricState}
import zio.test.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
    score = None,
    rules = Nil,
  )

  /** The service's role owns the table. */
  private val owned = TableOwnership(owner = "auth", role = "auth", mayAlter = true)

  private case class Env(repository: Stub[AntifraudEventRepository], configService: Stub[OAuthConfigurationService])

  private def env: Env = Env(stub[AntifraudEventRepository], stub[OAuthConfigurationService])

  private def build(
      e: Env,
      settings: AntifraudEvents.Settings,
      config: versola.util.CoreConfig = TestEnvConfig.coreConfig,
      antifraud: Antifraud = Antifraud.off,
      velocity: VelocityCounters = AntifraudTestSettings.velocityOff,
      tuning: Tuning = AntifraudTestSettings.tuning,
      sizing: AntifraudEvents.Sizing = smallSizing,
      asn: AsnResolver = AsnResolver.off,
      geo: GeoResolver = GeoResolver.off,
      ownership: Task[TableOwnership] = ZIO.succeed(owned),
      moved: Long = 0L,
  ): ZIO[Scope, Nothing, AntifraudEvents] =
    (for
      _ <- e.repository.ownership.returnsZIO(_ => ownership)
      _ <- e.repository.maintainPartitions.succeedsWith(moved)
      _ <- e.repository.rollupStats.succeedsWith(true)
      _ <- e.repository.partitionDaysAhead.succeedsWith(0)
      _ <- e.repository.pruneStats.succeedsWith(())
      _ <- e.repository.pruneEvents.succeedsWith(())
      events <- AntifraudEvents.liveWith(sizing).build.map(_.get[AntifraudEvents])
    yield events).provideSome[Scope](
      ZLayer.succeed[AntifraudEventRepository](e.repository),
      ZLayer.succeed(settings),
      ZLayer.succeed(config),
      ZLayer.succeed[OAuthConfigurationService](e.configService),
      ZLayer.succeed[Antifraud](antifraud),
      ZLayer.succeed[AntifraudTuning](AntifraudTestSettings.fixed(tuning)),
      ZLayer.succeed[VelocityCounters](velocity),
      ZLayer.succeed[AsnResolver](asn),
      ZLayer.succeed[GeoResolver](geo),
      SecureRandom.live,
      SecureRandom.live >>> SecurityService.live,
    )

  /** The ASN database of the specs: a hosting provider, a carrier, and nothing for the rest (`100.64.0.1`). */
  private val resolver: AsnResolver = AsnResolver(
    AsnDatabaseLoader.fixed(
      AsnDatabase
        .read(
          ByteArrayInputStream(
            List(
              "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS Hetzner Online GmbH",
              "203.0.113.0\t203.0.113.255\t64500\tKZ\tKAZTELECOM-AS JSC Kazakhtelecom",
            ).mkString("\n").getBytes(StandardCharsets.UTF_8),
          ),
        )
        .database,
    ),
    AsnClassifier(AsnClassifier.defaultKeywords),
  )

  /** The two databases of the country signal: ASN ranges (a hosting provider registered in DE, a carrier in KZ, a second carrier
    * in AM and a cloud in US that the country database knows nothing of, and one with no registration country), and country
    * ranges that disagree with the carrier's registration (UZ for the KZ one, in lower case, as a file may spell it) and cover
    * an address that no AS announces (TR). `100.64.0.1` is in neither country range. */
  private val countryAsn: AsnResolver = AsnResolver(
    AsnDatabaseLoader.fixed(
      AsnDatabase
        .read(
          ByteArrayInputStream(
            List(
              "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS Hetzner Online GmbH",
              "203.0.113.0\t203.0.113.255\t64500\tKZ\tKAZTELECOM-AS JSC Kazakhtelecom",
              "192.0.2.0\t192.0.2.255\t64501\tAM\tARMENTEL Example Telecom",
              "198.18.0.0\t198.18.255.255\t64502\tUS\tEXAMPLE-CLOUD Example Cloud Hosting",
              "100.64.0.0\t100.64.0.255\t64503\tZZ\tNO-COUNTRY Example Carrier",
            ).mkString("\n").getBytes(StandardCharsets.UTF_8),
          ),
        )
        .database,
    ),
    AsnClassifier(AsnClassifier.defaultKeywords),
  )

  private val countries: GeoResolver = GeoResolver(
    RangeDatabaseLoader.fixed(
      RangeDatabase
        .read(
          ByteArrayInputStream(
            List(
              "5.45.64.0,5.45.79.255,FR",
              "203.0.113.0,203.0.113.255,uz",
              "198.51.100.0,198.51.100.255,TR",
            ).mkString("\n").getBytes(StandardCharsets.UTF_8),
          ),
          DbIpCountryFormat,
        )
        .database,
    ),
  )

  private val t0 = Instant.parse("2026-10-21T10:00:30Z")

  private val small = AntifraudTestSettings.events

  /** A queue that fills up and batches that fill. */
  private val smallSizing = AntifraudTestSettings.sizing.copy(queueCapacity = 3, batchSize = 10, flushInterval = 1.second, partitionsAhead = 3)

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
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10, batchSize = 2))
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
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 100, batchSize = 2))
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
          _ <- e.repository.ownership.succeedsWith(owned)
          _ <- e.repository.maintainPartitions.succeedsWith(0L)
          _ <- e.repository.insertBatch.succeedsWith(())
          _ <- e.repository.rollupStats.succeedsWith(true)
          _ <- e.repository.partitionDaysAhead.succeedsWith(0)
          _ <- e.repository.pruneStats.succeedsWith(())
          events <- AntifraudEvents.liveWith(smallSizing).build.map(_.get[AntifraudEvents]).provideSome[Scope](
            ZLayer.succeed[AntifraudEventRepository](e.repository),
            ZLayer.succeed(small),
            ZLayer.succeed(TestEnvConfig.coreConfig),
            ZLayer.succeed[OAuthConfigurationService](e.configService),
            ZLayer.succeed[Antifraud](Antifraud.off),
            ZLayer.succeed[AntifraudTuning](AntifraudTuning.off),
            ZLayer.succeed[VelocityCounters](AntifraudTestSettings.velocityOff),
            ZLayer.succeed[AsnResolver](AsnResolver.off),
            ZLayer.succeed[GeoResolver](GeoResolver.off),
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
            _ <- e.repository.ownership.succeedsWith(owned)
            _ <- e.repository.maintainPartitions.returnsZIO: _ =>
              attempts.updateAndGet(_ + 1).flatMap(n => ZIO.fail(RuntimeException("lock timeout")).when(n == 1).as(0L))
            _ <- e.repository.insertBatch.succeedsWith(())
            _ <- e.repository.rollupStats.succeedsWith(true)
            _ <- e.repository.partitionDaysAhead.succeedsWith(0)
            _ <- e.repository.pruneStats.succeedsWith(())
            _ <- AntifraudEvents.liveWith(smallSizing).build.provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              ZLayer.succeed[Antifraud](Antifraud.off),
              ZLayer.succeed[AntifraudTuning](AntifraudTuning.off),
              ZLayer.succeed[VelocityCounters](AntifraudTestSettings.velocityOff),
              ZLayer.succeed[AsnResolver](AsnResolver.off),
              ZLayer.succeed[GeoResolver](GeoResolver.off),
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
      test("the days ahead that have a partition are a gauge, set after every run, a failed one included") {
        val e = env
        val gauge = Metric.gauge("antifraud_partition_days_ahead")
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:00Z"))
            attempts <- Ref.make(0)
            _ <- e.repository.ownership.succeedsWith(owned)
            _ <- e.repository.maintainPartitions.returnsZIO: _ =>
              attempts.updateAndGet(_ + 1).flatMap(n => ZIO.fail(RuntimeException("lock timeout")).when(n == 2).as(0L))
            ahead <- Ref.make(7)
            _ <- e.repository.partitionDaysAhead.returnsZIO(_ => ahead.get)
            _ <- e.repository.insertBatch.succeedsWith(())
            _ <- e.repository.rollupStats.succeedsWith(true)
            _ <- e.repository.pruneStats.succeedsWith(())
            _ <- AntifraudEvents.liveWith(smallSizing).build.provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              ZLayer.succeed[Antifraud](Antifraud.off),
              ZLayer.succeed[AntifraudTuning](AntifraudTuning.off),
              ZLayer.succeed[VelocityCounters](AntifraudTestSettings.velocityOff),
              ZLayer.succeed[AsnResolver](AsnResolver.off),
              ZLayer.succeed[GeoResolver](GeoResolver.off),
              SecureRandom.live,
              SecureRandom.live >>> SecurityService.live,
            )
            afterFirst <- gauge.value
            // The next run (after six hours) fails, and the days ahead have shrunk meanwhile: the gauge says so all the same.
            _ <- ahead.set(2)
            _ <- TestClock.adjust(6.hours)
            afterFailed <- gauge.value
            // The run after that, a minute later, makes it good.
            _ <- ahead.set(7)
            _ <- TestClock.adjust(1.minute)
            afterRetry <- gauge.value
            seen = e.repository.partitionDaysAhead.calls
          yield assertTrue(
            afterFirst == MetricState.Gauge(7.0),
            afterFailed == MetricState.Gauge(2.0),
            afterRetry == MetricState.Gauge(7.0),
            seen.nonEmpty,
          )
      },
      test("rows moved out of the default partition are counted: a day ran without a partition") {
        val e = env
        val counter = Metric.counter("antifraud_events_moved_from_default_total")
        ZIO.scoped:
          for
            before <- counter.value
            _ <- build(e, small, moved = 12)
            after <- counter.value
          yield assertTrue(after.count - before.count == 12.0)
      },
      test("a run that moved nothing counts nothing") {
        val e = env
        val counter = Metric.counter("antifraud_events_moved_from_default_total")
        ZIO.scoped:
          for
            before <- counter.value
            _ <- build(e, small)
            after <- counter.value
          yield assertTrue(after == before)
      },
      test("a service that does not own the table says what to run, sets the gauge, and attempts no partition DDL") {
        val e = env
        val gauge = Metric.gauge("antifraud_partition_owner_mismatch")
        ZIO.scoped:
          for
            _ <- build(e, small, ownership = ZIO.succeed(TableOwnership(owner = "migrator", role = "versola-auth", mayAlter = false)))
            errors <- ZTestLogger.logOutput.map(_.filter(_.logLevel == LogLevel.Error).map(_.message()))
            mismatch <- gauge.value
          yield assertTrue(
            e.repository.maintainPartitions.calls.isEmpty,
            mismatch == MetricState.Gauge(1.0),
            errors.size == 1,
            errors.head.contains("owned by migrator"),
            errors.head.contains("connects as versola-auth"),
            // a name that is not a plain identifier is quoted, so the statement can be pasted as it is
            errors.head.contains("ALTER TABLE af_event OWNER TO \"versola-auth\""),
          )
      },
      test("a role that owns the table clears the gauge, and the ownership is checked again at the next run") {
        val e = env
        val gauge = Metric.gauge("antifraud_partition_owner_mismatch")
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:00Z"))
            owner <- Ref.make(TableOwnership(owner = "migrator", role = "auth", mayAlter = false))
            _ <- build(e, small, ownership = owner.get)
            during <- gauge.value
            skipped = e.repository.maintainPartitions.calls.size
            _ <- owner.set(owned)
            _ <- TestClock.adjust(6.hours)
            after <- gauge.value
          yield assertTrue(
            during == MetricState.Gauge(1.0),
            skipped == 0,
            after == MetricState.Gauge(0.0),
            e.repository.maintainPartitions.calls.size == 1,
          )
      },
      test("a check of the owner that fails does not stop the maintenance") {
        val e = env
        ZIO.scoped:
          for
            _ <- build(e, small, ownership = ZIO.fail(RuntimeException("connection refused")))
          yield assertTrue(e.repository.maintainPartitions.calls.size == 1)
      },
      test("the service keeps seven days of partitions ahead") {
        assertTrue(AntifraudEvents.Sizing.standard.partitionsAhead == 7)
      },
      test("a failing run is logged and does not stop the writer") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.ownership.succeedsWith(owned)
            _ <- e.repository.maintainPartitions.failsWith(RuntimeException("no permission"))
            _ <- e.repository.insertBatch.succeedsWith(())
            _ <- e.repository.rollupStats.succeedsWith(true)
            _ <- e.repository.partitionDaysAhead.succeedsWith(0)
            _ <- e.repository.pruneStats.succeedsWith(())
            events <- AntifraudEvents.liveWith(smallSizing).build.map(_.get[AntifraudEvents]).provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              ZLayer.succeed[Antifraud](Antifraud.off),
              ZLayer.succeed[AntifraudTuning](AntifraudTuning.off),
              ZLayer.succeed[VelocityCounters](AntifraudTestSettings.velocityOff),
              ZLayer.succeed[AsnResolver](AsnResolver.off),
              ZLayer.succeed[GeoResolver](GeoResolver.off),
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
        val limited = AntifraudTestSettings.tuning.copy(smsLimits =
          SmsLimits(default = SmsLimit(sentPerHour = 2, minVolume = 0, minConversion = 0.1), byPrefix = Map.empty),
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, tuning = limited)
            _ <- ZIO.foreachDiscard(1 to 5)(_ => events.allowSms(clientId, phone, t0, charge = false))
            first <- events.allowSms(clientId, phone, t0, charge = true).map(_ != SmsBudget.Decision.Refused)
            second <- events.allowSms(clientId, Phone("+4915100000000"), t0, charge = true).map(_ != SmsBudget.Decision.Refused)
            third <- events.allowSms(clientId, Phone("+4915199999999"), t0, charge = true).map(_ != SmsBudget.Decision.Refused)
            readOnly <- events.allowSms(clientId, phone, t0, charge = false).map(_ != SmsBudget.Decision.Refused)
            otherRange <- events.allowSms(clientId, Phone("+4917112345678"), t0, charge = true).map(_ != SmsBudget.Decision.Refused)
            _ <- events.releaseSms(clientId, phone, t0)
            afterRelease <- events.allowSms(clientId, phone, t0, charge = false).map(_ != SmsBudget.Decision.Refused)
          yield assertTrue(first, second, !third, !readOnly, otherRange, afterRelease)
      },
      test("the conversion is only looked at where registration is closed for the number; the hourly limit applies everywhere") {
        val germany = PhoneRegion(region = "DE", dialCode = "+49", length = 11, regex = ".*", registration = true)
        val closedGermany = germany.copy(registration = false)
        def run(regions: List[PhoneRegion], limit: SmsLimit) =
          val e = env
          ZIO.scoped:
            for
              _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
              _ <- e.configService.getPhoneRegions.succeedsWith(regions)
              _ <- e.repository.insertBatch.succeedsWith(())
              events <- build(e, small, tuning = AntifraudTestSettings.tuning.copy(smsLimits = SmsLimits(default = limit, byPrefix = Map.empty)))
              _ <- ZIO.foreachDiscard(1 to 5)(_ => events.allowSms(clientId, phone, t0, charge = true))
              allowed <- events.allowSms(clientId, phone, t0, charge = false).map(_ != SmsBudget.Decision.Refused)
            yield allowed
        val breaker = SmsLimit(sentPerHour = 0, minVolume = 5, minConversion = 0.5)
        for
          open <- run(List(germany), breaker)
          closed <- run(List(closedGermany), breaker)
          limitOpen <- run(List(germany), SmsLimit(sentPerHour = 5, minVolume = 0, minConversion = 0.1))
        yield assertTrue(open, !closed, !limitOpen)
      },
      test("a range whose codes are all passed by passkey stays open, and one whose codes nobody passes is closed") {
        val closedGermany = PhoneRegion(region = "DE", dialCode = "+49", length = 11, regex = ".*", registration = false)
        val breaker = SmsLimit(sentPerHour = 0, minVolume = 5, minConversion = 0.5)
        def run(passed: AntifraudOutcome | Null) =
          val e = env
          ZIO.scoped:
            for
              _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
              _ <- e.configService.getPhoneRegions.succeedsWith(List(closedGermany))
              _ <- e.repository.insertBatch.succeedsWith(())
              _ <- TestClock.setTime(t0)
              events <- build(e, small, tuning = AntifraudTestSettings.tuning.copy(smsLimits = SmsLimits(default = breaker, byPrefix = Map.empty)))
              _ <- ZIO.foreachDiscard(1 to 5): _ =>
                events.allowSms(clientId, phone, t0, charge = true) *>
                  ZIO.foreachDiscard(Option(passed))(outcome => events.otpChecked(authId, clientId, None, Right(phone), outcome.nn))
              allowed <- events.allowSms(clientId, phone, t0, charge = false).map(_ != SmsBudget.Decision.Refused)
            yield allowed
        for
          byPasskey <- run(AntifraudOutcome.passkey_alternative)
          byCode <- run(AntifraudOutcome.verified)
          nobody <- run(null)
        yield assertTrue(byPasskey, byCode, !nobody)
      },
      test("with no budget configured nothing is looked up") {
        val e = env
        ZIO.scoped:
          for
            events <- build(e, small)
            allowed <- events.allowSms(clientId, phone, t0, charge = true).map(_ != SmsBudget.Decision.Refused)
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
            rolled == List((Instant.parse("2026-10-21T09:59:00Z"), Instant.parse("2026-10-21T10:01:00Z"), 4, 1.minute)),
            pruned == List(Instant.parse("2026-09-21T10:01:30Z")),
          )
      },
      test("only the instance that did the rollup prunes: a skipped run leaves the pruning to it") {
        val e = env
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:30Z"))
            _ <- e.repository.ownership.succeedsWith(owned)
            _ <- e.repository.maintainPartitions.succeedsWith(0L)
            _ <- e.repository.partitionDaysAhead.succeedsWith(0)
            _ <- e.repository.rollupStats.succeedsWith(false)
            _ <- e.repository.pruneStats.succeedsWith(())
            events <- AntifraudEvents.liveWith(smallSizing).build.provideSome[Scope](
              ZLayer.succeed[AntifraudEventRepository](e.repository),
              ZLayer.succeed(small),
              ZLayer.succeed(TestEnvConfig.coreConfig),
              ZLayer.succeed[OAuthConfigurationService](e.configService),
              ZLayer.succeed[Antifraud](Antifraud.off),
              ZLayer.succeed[AntifraudTuning](AntifraudTuning.off),
              ZLayer.succeed[VelocityCounters](AntifraudTestSettings.velocityOff),
              ZLayer.succeed[AsnResolver](AsnResolver.off),
              ZLayer.succeed[GeoResolver](GeoResolver.off),
              SecureRandom.live,
              SecureRandom.live >>> SecurityService.live,
            )
            _ <- TestClock.adjust(2.minutes)
          yield assertTrue(e.repository.rollupStats.calls.size == 2, e.repository.pruneStats.calls.isEmpty)
      },
      test("the interval must be a whole number of minutes, at least one") {
        assertTrue(
          AntifraudEvents.statsIntervalError(1.minute).isEmpty,
          AntifraudEvents.statsIntervalError(5.minutes).isEmpty,
          AntifraudEvents.statsIntervalError(90.seconds).isDefined,
          AntifraudEvents.statsIntervalError(30.seconds).isDefined,
          AntifraudEvents.statsIntervalError(Duration.Zero).isDefined,
          AntifraudEvents.statsIntervalError(61.seconds).isDefined,
        )
      },
      test("recomputes twice the interval when that is longer than the lookback, so every minute is rolled up twice") {
        val e = env
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:30Z"))
            _ <- build(e, small.copy(statsInterval = 5.minutes))
            _ <- TestClock.adjust(5.minutes)
            rolled = e.repository.rollupStats.calls
          yield assertTrue(rolled == List((Instant.parse("2026-10-21T09:55:00Z"), Instant.parse("2026-10-21T10:05:00Z"), 4, 5.minutes)))
      },
    ),
    suite("shadow mode")(
      test("a decision that would block is let through and counted as such, and its event keeps both actions") {
        val e = env
        val shadowed = Decision(DecisionAction.Allow, 80, List("deny_list:ip", "shadow:velocity_ip_1m"), DecisionAction.Block)
        val engine: Antifraud = _ => ZIO.succeed(shadowed)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            before <- Metric.counter("antifraud_decisions_total").tagged(Set(
              MetricLabel("action", "Allow"),
              MetricLabel("would_action", "Block"),
            )).value.map(_.count)
            ruleBefore <- Metric.counter("antifraud_rules_fired_total").tagged(Set(
              MetricLabel("rule", "velocity_ip_1m"),
              MetricLabel("shadow", "true"),
            )).value.map(_.count)
            decision <- events.decideOtpRequest(clientId, Some("Mozilla/5.0"), Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(decision))
            after <- Metric.counter("antifraud_decisions_total").tagged(Set(
              MetricLabel("action", "Allow"),
              MetricLabel("would_action", "Block"),
            )).value.map(_.count)
            ruleAfter <- Metric.counter("antifraud_rules_fired_total").tagged(Set(
              MetricLabel("rule", "velocity_ip_1m"),
              MetricLabel("shadow", "true"),
            )).value.map(_.count)
            details <- logContext.get.map(_.get(Observability.antifraud))
            _ <- TestClock.adjust(0.seconds)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            decision == shadowed,
            after - before >= 1.0,
            ruleAfter - ruleBefore >= 1.0,
            details.map(d => (d.decision, d.wouldAction)) == Some(("Allow", Some("Block"))),
            written.map(_.outcome) == List(AntifraudOutcome.sent),
            written.flatMap(_.decision) == List("Allow"),
            written.flatMap(_.wouldAction) == List("Block"),
            written.flatMap(_.score) == List(80),
            written.head.rules == shadowed.firedRules,
          )
      },
      test("an evaluation that cannot be built fails as failure-action says, in shadow only recorded, and is counted as an error") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.returnsZIO(_ => ZIO.die(RuntimeException("config is down")))
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true, failureAction = DecisionAction.Challenge)),
              tuning = AntifraudTestSettings.tuning.copy(mode = TuningMode.shadow),
            )
            shadowed <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(shadowed == Decision(DecisionAction.Allow, 0, List(Antifraud.ErrorRule), DecisionAction.Challenge))
      },
      test("a number whose range the budget would close is allowed in shadow, counted as refused, and the code is counted as sent") {
        val e = env
        val limited = SmsLimits(default = SmsLimit(sentPerHour = 2, minVolume = 0, minConversion = 0.1), byPrefix = Map.empty)
        def run(mode: TuningMode) =
          ZIO.scoped:
            for
              _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
              _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
              events <- build(e, small, tuning = AntifraudTestSettings.tuning.copy(mode = mode, smsLimits = limited))
              first <- events.allowSms(clientId, phone, t0, charge = true)
              second <- events.allowSms(clientId, phone, t0, charge = true)
              third <- events.allowSms(clientId, phone, t0, charge = true)
              fourth <- events.allowSms(clientId, phone, t0, charge = true)
            yield List(first, second, third, fourth)
        for
          before <- Metric.counter("antifraud_sms_budget_refused_total").tagged(Set(
            MetricLabel("reason", "cap"),
            MetricLabel("mode", "shadow"),
          )).value.map(_.count)
          shadowed <- run(TuningMode.shadow)
          after <- Metric.counter("antifraud_sms_budget_refused_total").tagged(Set(
            MetricLabel("reason", "cap"),
            MetricLabel("mode", "shadow"),
          )).value.map(_.count)
          enforced <- run(TuningMode.enforce)
        yield assertTrue(
          shadowed == List(SmsBudget.Decision.Allowed, SmsBudget.Decision.Allowed, SmsBudget.Decision.WouldRefuse, SmsBudget.Decision.WouldRefuse),
          after - before >= 2.0,
          enforced == List(SmsBudget.Decision.Allowed, SmsBudget.Decision.Allowed, SmsBudget.Decision.Refused, SmsBudget.Decision.Refused),
        )
      },
      test("the user agent reaches the rules whole, so that a ua_hash entry matches however long the header is") {
        val e = env
        val seen = new java.util.concurrent.atomic.AtomicReference[Option[AntifraudContext]](None)
        val engine: Antifraud = ctx => ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        val long = "x" * 1000
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(None)
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            _ <- events.decideOtpRequest(clientId, Some(long), Right(phone), repeated = false)
          yield assertTrue(seen.get.flatMap(_.userAgent) == Some(long))
      },
    ),
    suite("the wait of a fake send")(
      test("is zero while the engine is off, the configured default until a real send has been timed, then a sample of the real ones") {
        val e = env
        val on = small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true, fakeSendDelay = 400.millis))
        ZIO.scoped:
          for
            off <- build(e, small)
            offDelay <- off.fakeSendDelay
            events <- build(e, on)
            cold <- events.fakeSendDelay
            _ <- events.sendTook(1.second)
            _ <- events.sendTook(3.seconds)
            warm <- ZIO.foreach(1 to 50)(_ => events.fakeSendDelay)
          yield assertTrue(
            offDelay == Duration.Zero,
            cold == 400.millis,
            warm.toSet.subsetOf(Set(1.second, 3.seconds)),
            warm.toSet.size == 2,
          )
      },
      test("never repeats a constant once there is a sample: the ring forgets what is older than its last 256") {
        val latency = SendLatency()
        (1 to 300).foreach(i => latency.add(Duration.fromMillis(i.toLong)))
        val seen = (0 until 256).flatMap(i => latency.sample(i)).map(_.toMillis).toSet
        assertTrue(latency.sample(0).isDefined, SendLatency().sample(0).isEmpty, seen == (45L to 300L).toSet)
      },
    ),
    suite("the ring of send durations")(
      test("keeps working once its position has passed what an int holds") {
        val latency = SendLatency(start = Int.MaxValue.toLong - 2)
        (1 to 600).foreach(i => latency.add(Duration.fromMillis(i.toLong)))
        val picks = (Int.MinValue to Int.MaxValue by 99991).map(latency.sample)
        assertTrue(picks.forall(_.isDefined), (0 until 300).forall(i => latency.sample(i).isDefined))
      },
    ),
    suite("the pause as a challenge")(
      test("is counted as a challenge of the kind pause: challenged when the step is left, challenge_passed when the ask is honoured") {
        val e = env
        def count(outcome: String) =
          Metric.counter("antifraud_challenges_total").tagged(Set(MetricLabel("kind", "pause"), MetricLabel("outcome", outcome))).value.map(_.count)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(None)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small)
            challenged0 <- count("challenged")
            passed0 <- count("challenge_passed")
            d = Decision(DecisionAction.Challenge, 61, List("velocity_phone_1h"))
            _ <- events.challengeStep(authId, clientId, None, Right(phone), AntifraudOutcome.challenged, d, "pause", None)
            _ <- events.challengeStep(authId, clientId, None, Right(phone), AntifraudOutcome.challenge_passed, d, "pause", None)
            challenged1 <- count("challenged")
            passed1 <- count("challenge_passed")
          yield assertTrue(challenged1 - challenged0 >= 1.0, passed1 - passed0 >= 1.0)
      },
    ),
    suite("retention")(
      test("deletes the rows past the retention, to the minute, every few minutes") {
        val e = env
        ZIO.scoped:
          for
            _ <- TestClock.setTime(Instant.parse("2026-10-21T10:00:30Z"))
            _ <- e.repository.pruneEvents.succeedsWith(())
            _ <- build(e, small.copy(retention = 14.days))
            _ <- TestClock.adjust(AntifraudEvents.PruneInterval)
          yield assertTrue(e.repository.pruneEvents.calls == List(Instant.parse("2026-10-07T10:05:30Z")))
      },
      test("a failure to delete is the log's problem: it is tried again, and nothing else stops") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.repository.pruneEvents.failsWith(RuntimeException("lock"))
            _ <- build(e, small)
            _ <- TestClock.adjust(AntifraudEvents.PruneInterval)
            _ <- TestClock.adjust(AntifraudEvents.PruneInterval)
          yield assertTrue(e.repository.pruneEvents.calls.size == 2)
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
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            _ <- Observability.setIp("203.0.113.7")
            decision <- events.decideOtpRequest(clientId, Some("Mozilla/5.0"), Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.blocked, Some(decision))
            details <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(
            seen.get.flatMap(_.ip) == Some("203.0.113.7"),
            seen.get.flatMap(_.destPrefix) == Some("491512"),
            details == Some(
              Observability.AntifraudDetails(
                "otp_request",
                "Block",
                12,
                List("deny_list"),
                Some("491512"),
                Some("blocked"),
                None,
                None,
                Some("Block"),
              ),
            ),
          )
      },
      test("with the engine off no decision is made and the outcome alone is put on the log context") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
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
              Some("Challenge"),
            )),
            written.map(_.outcome) == List(AntifraudOutcome.challenged),
            written.map(_.action) == List(AntifraudAction.otp_request),
            written.flatMap(_.decision) == List("Challenge"),
          )
      },
    ),
    suite("velocity in the decision")(
      test("a sent code is counted against the number, and the limit fires on the request over it") {
        val e = env
        val tuned = AntifraudTestSettings.tuning.copy(
          blockAt = 50,
          thresholds = AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perMinute = 2, weight = 60)),
        )
        val vsettings = AntifraudTestSettings.velocity.copy(enabled = true)
        val velocity = VelocityCounters(vsettings, AntifraudTestSettings.velocityLimits, tuned.thresholds)
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Challenge),
          List(AntifraudTestSettings.velocityRules(vsettings, tuned)),
          tuned,
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true), velocity = vsettings),
              antifraud = engine,
              velocity = velocity,
              tuning = tuned,
            )
            first <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(first))
            second <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(second))
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
      test("a request that is challenged, faked or refused is not counted against the number; its source is, and a sent code is") {
        val e = env
        val tuned = AntifraudTestSettings.tuning.copy(
          thresholds = AntifraudTestSettings.thresholds.copy(
            phone = AntifraudTestSettings.limit.copy(perMinute = 2, weight = 60),
            prefix = AntifraudTestSettings.limit.copy(perMinute = 100, weight = 10),
          ),
        )
        val vsettings = AntifraudTestSettings.velocity.copy(enabled = true)
        val velocity = VelocityCounters(vsettings, AntifraudTestSettings.velocityLimits, tuned.thresholds)
        // Whatever the counts say, the request is challenged: the number must not pay for that.
        val challenging = new Antifraud.RuleSet:
          override def rules: UIO[List[AntifraudRule]] =
            ZIO.succeed(List(AntifraudRule(name = "always", weight = 0, forces = Some(DecisionAction.Challenge), test = _ => true)))
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Challenge),
          List(challenging, AntifraudTestSettings.velocityRules(vsettings, tuned)),
          tuned,
        )
        val client = TestEnvConfig.mtlsClient(clientId)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(client))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true), velocity = vsettings),
              antifraud = engine,
              velocity = velocity,
              tuning = tuned,
            )
            decisions <- ZIO.foreach(1 to 5)(_ => events.decideOtpRequest(clientId, None, Right(phone), repeated = false))
            hash <- events.subjectHash(Right(phone))
            now <- Clock.instant
            afterChallenges = velocity.count(VelocityKey.phone, hash, VelocityWindow.OneMinute, now)
            range = velocity.count(VelocityKey.prefix, s"${identity[String](client.tenantId)}/491512", VelocityWindow.OneMinute, now)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.faked, decisions.headOption)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.blocked, decisions.headOption)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.throttled, decisions.headOption)
            afterOthers = velocity.count(VelocityKey.phone, hash, VelocityWindow.OneMinute, now)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, decisions.headOption)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, decisions.headOption)
            afterSends = velocity.count(VelocityKey.phone, hash, VelocityWindow.OneMinute, now)
          yield assertTrue(
            decisions.forall(_.action == DecisionAction.Challenge),
            afterChallenges == 0,
            range == 5,
            afterOthers == 0,
            afterSends == 2,
          )
      },
      test("the mail domain of an address is in the signals, so an email_domain entry can match") {
        val e = env
        val byDomain = new Antifraud.RuleSet:
          override def rules: UIO[List[AntifraudRule]] =
            ZIO.succeed(
              List(
                AntifraudRule(
                  name = "deny_list:email_domain",
                  weight = 0,
                  forces = Some(DecisionAction.Block),
                  test = _.signals.get(AntifraudLists.EmailDomainSignal).contains("example.com"),
                ),
              ),
            )
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Challenge),
          List(byDomain),
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            listed <- events.decideOtpRequest(clientId, None, Left(Email("User@Example.COM")), repeated = false)
            other <- events.decideOtpRequest(clientId, None, Left(Email("user@example.org")), repeated = false)
            sms <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            listed.action == DecisionAction.Block,
            other.action == DecisionAction.Allow,
            sms.action == DecisionAction.Allow,
          )
      },
      test("a request made again with its answer is judged but not counted a second time") {
        val e = env
        val tuned = AntifraudTestSettings.tuning.copy(
          blockAt = 50,
          thresholds = AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 2, weight = 60)),
        )
        val vsettings = AntifraudTestSettings.velocity.copy(enabled = true)
        val velocity = VelocityCounters(vsettings, AntifraudTestSettings.velocityLimits, tuned.thresholds)
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Challenge),
          List(AntifraudTestSettings.velocityRules(vsettings, tuned)),
          tuned,
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true), velocity = vsettings),
              antifraud = engine,
              velocity = velocity,
              tuning = tuned,
            )
            _ <- Observability.setIp("203.0.113.7")
            first <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            again <- ZIO.foreach(1 to 3)(_ => events.decideOtpRequest(clientId, None, Right(phone), repeated = true))
            second <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            third <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            first.action == DecisionAction.Allow,
            again.forall(_.action == DecisionAction.Allow),
            second.action == DecisionAction.Allow,
            third.action == DecisionAction.Block,
            third.firedRules == List("velocity_ip_1m"),
          )
      },
      test("a challenged request made again with its answer is judged on the counts it is in, not as one request more, while a new one after it is") {
        val e = env
        // Challenge from 30; a request is the 3rd of the minute and so over its limit of 2 (30), and the 4th of the ten
        // minutes would be over that limit of 3 too (60, Block).
        val tuned = AntifraudTestSettings.tuning.copy(
          challengeAt = 30,
          blockAt = 50,
          thresholds = AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 2, perTenMinutes = 3, weight = 30)),
        )
        val vsettings = AntifraudTestSettings.velocity.copy(enabled = true)
        val velocity = VelocityCounters(vsettings, AntifraudTestSettings.velocityLimits, tuned.thresholds)
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Allow),
          List(AntifraudTestSettings.velocityRules(vsettings, tuned)),
          tuned,
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true), velocity = vsettings),
              antifraud = engine,
              velocity = velocity,
              tuning = tuned,
            )
            _ <- Observability.setIp("203.0.113.7")
            first <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            second <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            challenged <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            answered <- events.decideOtpRequest(clientId, None, Right(phone), repeated = true)
            answeredAgain <- events.decideOtpRequest(clientId, None, Right(phone), repeated = true)
            next <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            first.action == DecisionAction.Allow,
            second.action == DecisionAction.Allow,
            challenged.action == DecisionAction.Challenge,
            challenged.firedRules == List("velocity_ip_1m"),
            // its own record does not tip it over the other limit
            answered == challenged,
            answeredAgain == challenged,
            // a new request after it is the 4th, and judged as such
            next.action == DecisionAction.Block,
            next.firedRules == List("velocity_ip_1m", "velocity_ip_10m"),
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
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10))
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
      test("a code page left for a passkey is written as a verification event with its own outcome, of the number it was asked for") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(None)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10))
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.faked)
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.passkey_alternative)
            _ <- TestClock.adjust(0.seconds)
            _ <- TestClock.adjust(1.second)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            written.map(_.outcome) == List(AntifraudOutcome.faked, AntifraudOutcome.passkey_alternative),
            written(1).action == AntifraudAction.otp_verify,
            written(1).conversationId == authId,
            written(1).subjectHash == written(0).subjectHash,
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
      test("an address has no prefix, is hashed case-insensitively, and the user agent is kept whole (a `ua_hash` entry is the hash of all of it)") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(None)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10))
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
            written.head.userAgent == Some(long),
          )
      },
    ),
    suite("the ASN signal")(
      test(
        "the context carries the AS of the request's address and the class and country in the signals, and the engine is not told the address twice",
      ) {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine, asn = resolver)
            _ <- Observability.setIp("5.45.64.1")
            _ <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            hosting = seen.get
            _ <- Observability.setIp("203.0.113.7")
            _ <- events.decideOtpRequest(clientId, None, Left(Email("a@example.com")), repeated = false)
            carrier = seen.get
            _ <- Observability.setIp("100.64.0.1")
            _ <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            unknown = seen.get
          yield assertTrue(
            hosting.flatMap(_.asn).contains(24940),
            hosting.map(_.signals.get(AsnRules.ClassSignal)).contains(Some("hosting")),
            carrier.flatMap(_.asn).contains(64500),
            carrier.map(_.signals.get(AsnRules.ClassSignal)).contains(Some("other")),
            // The mail domain signal is still there next to them.
            carrier.map(_.signals.get(AntifraudLists.EmailDomainSignal)).contains(Some("example.com")),
            unknown.flatMap(_.asn).isEmpty,
            unknown.map(_.signals.contains(AsnRules.ClassSignal)).contains(false),
            // The country the ASN database gives is no signal of its own (the country signal is [[GeoRules]]'s).
            hosting.map(_.signals.keySet.filter(_.startsWith("asn_"))).contains(Set(AsnRules.ClassSignal)),
          )
      },
      test("without a database the context has no ASN and no signal, and the request goes on") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            _ <- Observability.setIp("5.45.64.1")
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(decision == Decision.allow, seen.get.exists(c => c.asn.isEmpty && !c.signals.contains(AsnRules.ClassSignal)))
      },
      test("a request that has no address (no proxy header set) has no ASN either, and is not looked up") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine, asn = resolver)
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            decision == Decision.allow,
            seen.get.exists(c => c.ip.isEmpty && c.asn.isEmpty && !c.signals.contains(AsnRules.ClassSignal)),
          )
      },
      test("the asn_hosting rule fires for a hosting network and not for a carrier, and adds its weight of 20 to the score") {
        val e = env
        val tuned = AntifraudTestSettings.tuning.copy(challengeAt = 20)
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Block),
          List(AsnRules.ruleSet),
          tuned,
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <-
              build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine, tuning = tuned, asn = resolver)
            _ <- Observability.setIp("5.45.64.1")
            hosting <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- Observability.setIp("203.0.113.7")
            carrier <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
          yield assertTrue(
            hosting == Decision(DecisionAction.Challenge, 20, List("asn_hosting")),
            carrier == Decision.allow,
          )
      },
      test("the per-AS velocity limit fires for requests from an AS and counts nothing for an address in no AS") {
        val e = env
        val tuned = AntifraudTestSettings.tuning.copy(
          blockAt = 50,
          thresholds = AntifraudTestSettings.thresholds.copy(asn = AntifraudTestSettings.limit.copy(perMinute = 2, weight = 60)),
        )
        val vsettings = AntifraudTestSettings.velocity.copy(enabled = true)
        val velocity = VelocityCounters(vsettings, AntifraudTestSettings.velocityLimits, tuned.thresholds)
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Challenge),
          List(AntifraudTestSettings.velocityRules(vsettings, tuned)),
          tuned,
        )
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true), velocity = vsettings),
              antifraud = engine,
              velocity = velocity,
              tuning = tuned,
              asn = resolver,
            )
            // Three different addresses of one AS: the limit is on the network, not on the address.
            _ <- Observability.setIp("5.45.64.1")
            first <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- Observability.setIp("5.45.64.2")
            second <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- Observability.setIp("5.45.70.9")
            third <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            // Another AS has its own count.
            _ <- Observability.setIp("203.0.113.7")
            elsewhere <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            // An address in no AS is not counted as one.
            _ <- ZIO.foreachDiscard(1 to 5)(_ =>
              Observability.setIp("100.64.0.1") *> events.decideOtpRequest(clientId, None, Right(phone), repeated = false),
            )
            now <- Clock.instant
            hetzner = velocity.count(VelocityKey.asn, "24940", VelocityWindow.OneMinute, now)
            kazakhtelecom = velocity.count(VelocityKey.asn, "64500", VelocityWindow.OneMinute, now)
          yield assertTrue(
            first.action == DecisionAction.Allow,
            second.action == DecisionAction.Allow,
            third.action == DecisionAction.Block,
            third.firedRules == List("velocity_asn_1m"),
            elsewhere.action == DecisionAction.Allow,
            // The third was refused, and a refused request is not counted.
            hetzner == 2,
            kazakhtelecom == 1,
          )
      },
      test("the event written for a step carries the AS and the class, and none where there is none, and never the address") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10), asn = resolver)
            _ <- Observability.setIp("5.45.64.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.verified)
            _ <- Observability.setIp("100.64.0.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- Observability.setIp("203.0.113.7")
            _ <- events.otpRequested(authId, clientId, None, Left(Email("a@example.com")), AntifraudOutcome.suppressed)
            _ <- TestClock.adjust(0.seconds)
            _ <- TestClock.adjust(1.second)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            written.map(_.asn) == List(Some(24940), Some(24940), None, Some(64500)),
            written.map(_.asnClass) == List(Some("hosting"), Some("hosting"), None, Some("other")),
            written.forall(w => !w.toString.contains("5.45.64.1") && !w.toString.contains("203.0.113.7")),
          )
      },
      test("an event of a request that has no address, or runs with no database, has no AS") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10))
            _ <- Observability.setIp("5.45.64.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- TestClock.adjust(0.seconds)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(written.map(_.asn) == List(None), written.map(_.asnClass) == List(None))
      },
      test("the AS and the class are on the request's log context, and the address is not repeated in them") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), asn = resolver)
            _ <- Observability.setIp("5.45.64.1")
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            afterDecision <- logContext.get.map(_.get(Observability.antifraud))
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(decision))
            afterEvent <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(
            afterDecision.flatMap(_.asn).contains(24940),
            afterDecision.flatMap(_.asnClass).contains("hosting"),
            // The step that follows adds its outcome and keeps what was there.
            afterEvent.flatMap(_.asn).contains(24940),
            afterEvent.flatMap(_.asnClass).contains("hosting"),
            afterEvent.flatMap(_.outcome).contains("sent"),
            afterEvent.exists(d => !d.toJson.contains("5.45.64.1")),
          )
      },
      test("a step that was not decided here (a check of a code) still puts the AS on the request's log context") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, asn = resolver)
            _ <- Observability.setIp("203.0.113.7")
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.wrong_code)
            details <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(details.flatMap(_.asn).contains(64500), details.flatMap(_.asnClass).contains("other"))
      },
      test("a request is counted once in the lookups: the decision looks the address up, the events only read it again") {
        val e = env
        def lookups(result: String) = Metric.counter("antifraud_asn_lookups_total").tagged(Set(MetricLabel("result", result))).value.map(_.count)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), asn = resolver)
            before <- lookups("hit")
            _ <- Observability.setIp("5.45.64.1")
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(decision))
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.verified)
            after <- lookups("hit")
          yield assertTrue(after - before == 1.0)
      },
      test("with the decision engine off the address is not looked up at all") {
        val e = env
        def lookups = Metric.counter("antifraud_asn_lookups_total").tagged(Set(MetricLabel("result", "hit"))).value.map(_.count)
        ZIO.scoped:
          for
            events <- build(e, small, asn = resolver)
            before <- lookups
            _ <- Observability.setIp("5.45.64.1")
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            after <- lookups
          yield assertTrue(decision == Decision.allow, after == before)
      },
    ),
    suite("the country signal")(
      test("the context carries the country the country database gives, whatever the AS is registered in, and none where it has none") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        def country(ip: String, events: AntifraudEvents) =
          Observability.setIp(ip) *> events.decideOtpRequest(clientId, None, Right(phone), repeated = false) *>
            ZIO.succeed(seen.get.flatMap(_.signals.get(GeoRules.CountrySignal)))
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)),
              antifraud = engine,
              asn = countryAsn,
              geo = countries,
            )
            // The country database's answer, not the registration of the AS (KZ); its lower-case code is the upper-case one.
            overRegistration <- country("203.0.113.7", events)
            // A hosting network gets the country database's answer too (not the DE of the AS).
            hostingWithGeo <- country("5.45.64.1", events)
            // No AS announces the address, and the country database knows it.
            noAs <- country("198.51.100.9", events)
            // The country database has nothing: no country, whatever the AS is registered in (AM for a carrier, US for a cloud).
            carrierRegistered <- country("192.0.2.5", events)
            hostingRegistered <- country("198.18.0.1", events)
            noCountry <- country("100.64.0.1", events)
            unknown <- country("172.31.0.1", events)
          yield assertTrue(
            overRegistration.contains("UZ"),
            hostingWithGeo.contains("FR"),
            noAs.contains("TR"),
            carrierRegistered.isEmpty,
            hostingRegistered.isEmpty,
            noCountry.isEmpty,
            unknown.isEmpty,
          )
      },
      test("the context carries the number's country and the tenant's regions in the signals, from a KZ number and a restricted tenant") {
        val kz = PhoneRegion(region = "KZ", dialCode = "+7", length = 10, regex = ".*", registration = true)
        val uz = PhoneRegion(region = "uz", dialCode = "+998", length = 9, regex = ".*", registration = false)
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(List(kz, uz, kz.copy(dialCode = "+77")))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            _ <- events.decideOtpRequest(clientId, None, Right(Phone("+77011234567")), repeated = false)
            sms = seen.get
            _ <- events.decideOtpRequest(clientId, None, Left(Email("a@example.com")), repeated = false)
            mail = seen.get
          yield assertTrue(
            sms.flatMap(_.signals.get(GeoRules.PhoneCountrySignal)).contains("KZ"),
            // Upper case, once each, in the order the tenant listed them.
            sms.flatMap(_.signals.get(GeoRules.TenantRegionsSignal)).contains("KZ,UZ"),
            // An email address has no number, and so no country of its own; the tenant's regions are still the tenant's.
            mail.exists(c => !c.signals.contains(GeoRules.PhoneCountrySignal)),
            mail.flatMap(_.signals.get(GeoRules.TenantRegionsSignal)).contains("KZ,UZ"),
          )
      },
      test("without phoneRegions there is no tenant_regions signal, and a number whose region cannot be told has no phone_country") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            _ <- events.decideOtpRequest(clientId, None, Right(Phone("+77011234567")), repeated = false)
            unrestricted = seen.get
            // Not a number of any region: the libphonenumber table has none for it.
            _ <- events.decideOtpRequest(clientId, None, Right(Phone("+10000000000")), repeated = false)
            unknown = seen.get
          yield assertTrue(
            unrestricted.exists(c => !c.signals.contains(GeoRules.TenantRegionsSignal)),
            unrestricted.flatMap(_.signals.get(GeoRules.PhoneCountrySignal)).contains("KZ"),
            unknown.exists(c => !c.signals.contains(GeoRules.PhoneCountrySignal)),
          )
      },
      test("country_mismatch fires for a KZ number asked for from a carrier in UZ, not from a hosting network, nor from an unknown address") {
        val e = env
        val tuned = AntifraudTestSettings.tuning.copy(challengeAt = 20)
        val engine = AntifraudTestSettings.engine(
          AntifraudTestSettings.decision.copy(enabled = true, timeout = 1.second, failureAction = DecisionAction.Block),
          List(GeoRules.ruleSet),
          tuned,
        )
        val kz = PhoneRegion(region = "KZ", dialCode = "+7", length = 10, regex = ".*", registration = true)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(List(kz))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)),
              antifraud = engine,
              tuning = tuned,
              asn = countryAsn,
              geo = countries,
            )
            kzNumber = Right(Phone("+77011234567"))
            // The carrier's range is UZ in the country database: both rules fire, 20 each.
            _ <- Observability.setIp("203.0.113.7")
            carrier <- events.decideOtpRequest(clientId, None, kzNumber, repeated = false)
            // The hosting range is FR there, and a VPN exit says nothing about the person.
            _ <- Observability.setIp("5.45.64.1")
            hosting <- events.decideOtpRequest(clientId, None, kzNumber, repeated = false)
            // The country database has no range for this one.
            _ <- Observability.setIp("192.0.2.5")
            noCountry <- events.decideOtpRequest(clientId, None, kzNumber, repeated = false)
          yield assertTrue(
            carrier == Decision(DecisionAction.Challenge, 40, List("country_mismatch", "country_outside_regions")),
            hosting == Decision.allow,
            noCountry == Decision.allow,
          )
      },
      test("with the ASN database alone, whatever it knows of the AS and its country, there is no country signal") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine, asn = countryAsn)
            _ <- Observability.setIp("5.45.64.1")
            _ <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            hosting = seen.get
            _ <- Observability.setIp("203.0.113.7")
            _ <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            carrier = seen.get
          yield assertTrue(
            hosting.flatMap(_.asn).contains(24940),
            hosting.exists(c => !c.signals.contains(GeoRules.CountrySignal)),
            carrier.flatMap(_.asn).contains(64500),
            carrier.exists(c => !c.signals.contains(GeoRules.CountrySignal)),
          )
      },
      test("without the databases there is no country signal, with only the ASN database none either, and the AS is still found") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            none <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine)
            _ <- Observability.setIp("203.0.113.7")
            _ <- none.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            withoutDatabases = seen.get
            asnOnly <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), antifraud = engine, asn = countryAsn)
            _ <- asnOnly.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            carrier = seen.get
          yield assertTrue(
            withoutDatabases.exists(c => !c.signals.contains(GeoRules.CountrySignal)),
            carrier.exists(c => c.asn.contains(64500) && !c.signals.contains(GeoRules.CountrySignal)),
          )
      },
      test("a request that has no address has no country, and the lookups are not made") {
        val e = env
        val seen = new AtomicReference[Option[AntifraudContext]](None)
        val engine = new Antifraud:
          def evaluate(ctx: AntifraudContext): UIO[Decision] = ZIO.succeed(seen.set(Some(ctx))).as(Decision.allow)
        def lookups = Metric.counter("antifraud_geo_lookups_total").tagged(Set(MetricLabel("result", "hit"))).value.map(_.count)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)),
              antifraud = engine,
              asn = countryAsn,
              geo = countries,
            )
            before <- lookups
            _ <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            after <- lookups
          yield assertTrue(seen.get.exists(c => c.ip.isEmpty && !c.signals.contains(GeoRules.CountrySignal)), after == before)
      },
      test("the event written for a step carries the country, and none where there is none, and never the address") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10), asn = countryAsn, geo = countries)
            _ <- Observability.setIp("5.45.64.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.verified)
            _ <- Observability.setIp("172.31.0.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- Observability.setIp("203.0.113.7")
            _ <- events.otpRequested(authId, clientId, None, Left(Email("a@example.com")), AntifraudOutcome.suppressed)
            _ <- Observability.setIp("198.18.0.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            // In an AS registered in AM, which the country database has no range for: no country, not AM.
            _ <- Observability.setIp("192.0.2.5")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- TestClock.adjust(0.seconds)
            _ <- TestClock.adjust(1.second)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(
            written.map(_.country) == List(Some("FR"), Some("FR"), None, Some("UZ"), None, None),
            written.forall(w => !w.toString.contains("5.45.64.1") && !w.toString.contains("203.0.113.7")),
          )
      },
      test("an event of a request with no address, or that runs with no database, has no country") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, sizing = smallSizing.copy(queueCapacity = 10))
            _ <- Observability.setIp("5.45.64.1")
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent)
            _ <- TestClock.adjust(0.seconds)
            written = e.repository.insertBatch.calls.flatten
          yield assertTrue(written.map(_.country) == List(None))
      },
      test("the country is on the request's log context after the decision and after the step that follows, with no address in it") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(
              e,
              small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)),
              asn = countryAsn,
              geo = countries,
            )
            _ <- Observability.setIp("203.0.113.7")
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            afterDecision <- logContext.get.map(_.get(Observability.antifraud))
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(decision))
            afterEvent <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(
            afterDecision.flatMap(_.country).contains("UZ"),
            afterEvent.flatMap(_.country).contains("UZ"),
            afterEvent.exists(d => !d.toJson.contains("203.0.113.7")),
          )
      },
      test("a step that was not decided here (a check of a code) still puts the country on the request's log context") {
        val e = env
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small, asn = countryAsn, geo = countries)
            _ <- Observability.setIp("198.51.100.9")
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.wrong_code)
            details <- logContext.get.map(_.get(Observability.antifraud))
          yield assertTrue(details.flatMap(_.country).contains("TR"))
      },
      test("a request is counted once in the geo lookups: the decision looks the address up, the events only read it again") {
        val e = env
        def lookups(result: String) = Metric.counter("antifraud_geo_lookups_total").tagged(Set(MetricLabel("result", result))).value.map(_.count)
        ZIO.scoped:
          for
            _ <- e.configService.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId)))
            _ <- e.configService.getPhoneRegions.succeedsWith(Nil)
            _ <- e.repository.insertBatch.succeedsWith(())
            events <- build(e, small.copy(decision = AntifraudTestSettings.decision.copy(enabled = true)), geo = countries)
            before <- lookups("hit")
            _ <- Observability.setIp("198.51.100.9")
            decision <- events.decideOtpRequest(clientId, None, Right(phone), repeated = false)
            _ <- events.otpRequested(authId, clientId, None, Right(phone), AntifraudOutcome.sent, Some(decision))
            _ <- events.otpChecked(authId, clientId, None, Right(phone), AntifraudOutcome.verified)
            after <- lookups("hit")
          yield assertTrue(after - before == 1.0)
      },
    ),
  ) @@ TestAspect.sequential
