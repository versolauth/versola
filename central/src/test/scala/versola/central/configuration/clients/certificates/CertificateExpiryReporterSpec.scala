package versola.central.configuration.clients.certificates

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.est.ClientEstRepository
import versola.central.configuration.edges.EdgeCertificateRepository
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.CertificateMetrics
import versola.util.CertificateMetrics.{Expiry, Kind}
import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.time.Instant

/** The gauges `CertificateExpiryReporter` sets. They are process-wide and this spec is the only writer of them, and it runs its tests one
  * at a time. */
object CertificateExpiryReporterSpec extends ZIOSpecDefault, ZIOStubs:

  private val now = Instant.parse("2026-11-15T12:00:00Z")

  private val config = TestCentralConfig.config.copy(
    // `renew-before` counts where a CA is configured, as `ClientCertificateService` reads it; without one the default is what renews
    clientCertificates = Some(
      CentralConfig.ClientCertificatesConfig(renewBefore = 2.days, certManager = Some(CentralConfig.CertManagerConfig(issuerName = "ca"))),
    ),
    clientIdentity = CentralConfig.ClientIdentityConfig(renewWindow = 0.25),
  )

  private val clients = Expiry(tracked = 40, earliestNotAfter = Some(now.plusSeconds(3600)), inRenewalWindow = 3, pastRenewalMidpoint = 1)
  private val edges = Expiry(tracked = 5, earliestNotAfter = Some(now.plusSeconds(7200)), inRenewalWindow = 0, pastRenewalMidpoint = 0)
  private val est = Expiry(tracked = 900, earliestNotAfter = Some(now.plusSeconds(86400)), inRenewalWindow = 12, pastRenewalMidpoint = 4)

  private class Stores:
    val issuances = stub[ClientCertificateIssuanceRepository]
    val edgeCertificates = stub[EdgeCertificateRepository]
    val estCertificates = stub[ClientEstRepository]
    val reporter = CertificateExpiryReporter.Impl(issuances, edgeCertificates, estCertificates, config)

    def returning(client: Expiry, edge: Expiry, estimate: Expiry): UIO[Unit] =
      issuances.expiry.succeedsWith(client) *> edgeCertificates.expiry.succeedsWith(edge) *> estCertificates.expiry.succeedsWith(estimate)

  private def gauge(name: String, kind: Kind): UIO[Double] =
    Metric.gauge(name).tagged(Set(MetricLabel("kind", kind.label))).value.map(_.value)

  private def published(kind: Kind): UIO[Expiry] =
    for
      tracked <- gauge("certificates_tracked", kind)
      inWindow <- gauge("certificates_in_renewal_window", kind)
      past <- gauge("certificates_past_renewal_midpoint", kind)
      earliest <- gauge("certificate_earliest_not_after_timestamp_seconds", kind)
    yield Expiry(tracked.toLong, Option.when(earliest > 0)(Instant.ofEpochSecond(earliest.toLong)), inWindow.toLong, past.toLong)

  def spec = suite("CertificateExpiryReporter")(
    test("publishes each kind's aggregate as gauges of that kind and no other label") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        _ <- stores.reporter.report
        client <- published(Kind.Client)
        edge <- published(Kind.Edge)
        estimate <- published(Kind.Est)
      yield assertTrue(client == clients, edge == edges, estimate == est)
    },
    test("empty stores publish zeros, also over what an earlier read had published") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        _ <- stores.reporter.report
        _ <- stores.returning(Expiry.Empty, Expiry.Empty, Expiry.Empty)
        _ <- stores.reporter.report
        client <- published(Kind.Client)
        edge <- published(Kind.Edge)
        estimate <- published(Kind.Est)
        earliest <- gauge("certificate_earliest_not_after_timestamp_seconds", Kind.Client)
      yield assertTrue(client == Expiry.Empty, edge == Expiry.Empty, estimate == Expiry.Empty, earliest == 0.0)
    },
    test("reads each store at the instant of the pass with the window its holder renews by") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        _ <- stores.reporter.report
      yield assertTrue(
        // central's `renew-before`
        stores.issuances.expiry.calls == List((now, 2.days)),
        // an edge renews with a third of its life left, as `EdgeIdentityEnrollment` does
        stores.edgeCertificates.expiry.calls == List((now, 1.0 / 3)),
        // `client-identity.renew-window`
        stores.estCertificates.expiry.calls == List((now, 0.25)),
      )
    },
    test("with no CA configured the window is the default one, as the service that renews reads it") {
      val stores = Stores()
      val unconfigured = CertificateExpiryReporter.Impl(
        stores.issuances,
        stores.edgeCertificates,
        stores.estCertificates,
        config.copy(clientCertificates = Some(CentralConfig.ClientCertificatesConfig(renewBefore = 2.days))),
      )
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        _ <- unconfigured.report
      yield assertTrue(stores.issuances.expiry.calls == List((now, CentralConfig.ClientCertificatesConfig().renewBefore)))
    },
    test("a store that cannot be read keeps its last values and the other kinds are still published") {
      val stores = Stores()
      val moved = est.copy(tracked = 901)
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        _ <- stores.reporter.report
        _ <- stores.issuances.expiry.failsWith(RuntimeException("db down"))
        _ <- stores.edgeCertificates.expiry.succeedsWith(Expiry.Empty)
        _ <- stores.estCertificates.expiry.succeedsWith(moved)
        _ <- stores.reporter.report
        client <- published(Kind.Client)
        edge <- published(Kind.Edge)
        estimate <- published(Kind.Est)
      yield assertTrue(client == clients, edge == Expiry.Empty, estimate == moved)
    },
    test("a renewal window of a client identity outside 0 to 1 is held to it") {
      val stores = Stores()
      val wide = CertificateExpiryReporter.Impl(
        stores.issuances,
        stores.edgeCertificates,
        stores.estCertificates,
        config.copy(clientIdentity = CentralConfig.ClientIdentityConfig(renewWindow = 7.0)),
      )
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        _ <- wide.report
      yield assertTrue(stores.estCertificates.expiry.calls == List((now, 1.0)))
    },
    test("reporting publishes at once and again every interval, for as long as the layer lives") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(clients, edges, est)
        counts <- ZIO.scoped:
          for
            _ <- (ZLayer.succeed[CertificateExpiryReporter](stores.reporter) >>> CertificateExpiryReporter.reporting).build
            // the pass runs on its own fiber: wait for it, bounded by the test's timeout
            first <- ZIO.succeed(stores.issuances.expiry.calls.size).repeatUntil(_ >= 1)
            _ <- TestClock.adjust(CertificateExpiryReporter.Interval)
            second <- ZIO.succeed(stores.issuances.expiry.calls.size).repeatUntil(_ >= 2)
          yield (first, second)
      yield assertTrue(counts == (1, 2))
    },
  ) @@ TestAspect.sequential @@ TestAspect.silentLogging
