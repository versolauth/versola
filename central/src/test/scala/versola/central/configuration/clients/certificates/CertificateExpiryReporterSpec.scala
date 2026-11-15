package versola.central.configuration.clients.certificates

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.est.ClientEstRepository
import versola.central.configuration.edges.EdgeCertificateRepository
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.CertificateMetrics.Kind
import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.time.Instant

/** The gauge `CertificateExpiryReporter` sets. It is process-wide and this spec is the only writer of it, and it runs its tests one at a
  * time. */
object CertificateExpiryReporterSpec extends ZIOSpecDefault, ZIOStubs:

  private val now = Instant.parse("2026-11-15T12:00:00Z")

  private val config = TestCentralConfig.config.copy(
    // `renew-before` counts where a CA is configured, as `ClientCertificateService` reads it; without one the default is what renews
    clientCertificates = Some(
      CentralConfig.ClientCertificatesConfig(renewBefore = 2.days, certManager = Some(CentralConfig.CertManagerConfig(issuerName = "ca"))),
    ),
    clientIdentity = CentralConfig.ClientIdentityConfig(renewWindow = 0.25),
  )

  private class Stores:
    val issuances = stub[ClientCertificateIssuanceRepository]
    val edgeCertificates = stub[EdgeCertificateRepository]
    val estCertificates = stub[ClientEstRepository]
    val reporter = CertificateExpiryReporter.Impl(issuances, edgeCertificates, estCertificates, config)

    def returning(client: Long, edge: Long, estimate: Long): UIO[Unit] =
      issuances.pastRenewalMidpoint.succeedsWith(client) *> edgeCertificates.pastRenewalMidpoint.succeedsWith(edge) *>
        estCertificates.pastRenewalMidpoint.succeedsWith(estimate)

  private def published(kind: Kind): UIO[Double] =
    Metric.gauge("certificates_past_renewal_midpoint").tagged(Set(MetricLabel("kind", kind.label))).value.map(_.value)

  def spec = suite("CertificateExpiryReporter")(
    test("publishes each kind's count as the gauge of that kind and no other label") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(client = 3, edge = 0, estimate = 12)
        _ <- stores.reporter.report
        client <- published(Kind.Client)
        edge <- published(Kind.Edge)
        estimate <- published(Kind.Est)
      yield assertTrue(client == 3.0, edge == 0.0, estimate == 12.0)
    },
    test("nothing past the middle publishes zero, also over what an earlier read had published") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(client = 3, edge = 2, estimate = 12)
        _ <- stores.reporter.report
        _ <- stores.returning(client = 0, edge = 0, estimate = 0)
        _ <- stores.reporter.report
        client <- published(Kind.Client)
        edge <- published(Kind.Edge)
        estimate <- published(Kind.Est)
      yield assertTrue(client == 0.0, edge == 0.0, estimate == 0.0)
    },
    test("reads each store at the instant of the pass with the window its holder renews by") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(client = 1, edge = 1, estimate = 1)
        _ <- stores.reporter.report
      yield assertTrue(
        // central's `renew-before`
        stores.issuances.pastRenewalMidpoint.calls == List((now, 2.days)),
        // an edge renews with a third of its life left, as `EdgeIdentityEnrollment` does
        stores.edgeCertificates.pastRenewalMidpoint.calls == List((now, 1.0 / 3)),
        // `client-identity.renew-window`
        stores.estCertificates.pastRenewalMidpoint.calls == List((now, 0.25)),
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
        _ <- stores.returning(client = 1, edge = 1, estimate = 1)
        _ <- unconfigured.report
      yield assertTrue(stores.issuances.pastRenewalMidpoint.calls == List((now, CentralConfig.ClientCertificatesConfig().renewBefore)))
    },
    test("a store that cannot be read keeps its last value and the other kinds are still published") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(client = 3, edge = 2, estimate = 12)
        _ <- stores.reporter.report
        _ <- stores.issuances.pastRenewalMidpoint.failsWith(RuntimeException("db down"))
        _ <- stores.edgeCertificates.pastRenewalMidpoint.succeedsWith(0L)
        _ <- stores.estCertificates.pastRenewalMidpoint.succeedsWith(13L)
        _ <- stores.reporter.report
        client <- published(Kind.Client)
        edge <- published(Kind.Edge)
        estimate <- published(Kind.Est)
      yield assertTrue(client == 3.0, edge == 0.0, estimate == 13.0)
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
        _ <- stores.returning(client = 1, edge = 1, estimate = 1)
        _ <- wide.report
      yield assertTrue(stores.estCertificates.pastRenewalMidpoint.calls == List((now, 1.0)))
    },
    test("reporting publishes at once and again every interval, for as long as the layer lives") {
      val stores = Stores()
      for
        _ <- TestClock.setTime(now)
        _ <- stores.returning(client = 1, edge = 1, estimate = 1)
        counts <- ZIO.scoped:
          for
            _ <- (ZLayer.succeed[CertificateExpiryReporter](stores.reporter) >>> CertificateExpiryReporter.reporting).build
            // the pass runs on its own fiber: wait for it, bounded by the test's timeout
            first <- ZIO.succeed(stores.issuances.pastRenewalMidpoint.calls.size).repeatUntil(_ >= 1)
            _ <- TestClock.adjust(CertificateExpiryReporter.Interval)
            second <- ZIO.succeed(stores.issuances.pastRenewalMidpoint.calls.size).repeatUntil(_ >= 2)
          yield (first, second)
      yield assertTrue(counts == (1, 2))
    },
  ) @@ TestAspect.sequential @@ TestAspect.silentLogging
