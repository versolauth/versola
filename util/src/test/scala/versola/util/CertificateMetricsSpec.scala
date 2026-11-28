package versola.util

import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.nio.file.Files
import java.time.Instant
import java.util.Date

/** The gauges of a listener's own certificate. The ones of the certificates central holds are asserted where they are set, in
  * `CertificateExpiryReporterSpec`: a gauge is process-wide, and one name written by two specs would be raced for. */
object CertificateMetricsSpec extends ZIOSpecDefault:

  private val notBefore = Instant.parse("2026-01-01T00:00:00Z")
  private val notAfter = Instant.parse("2027-01-01T00:00:00Z")

  private def gauge(name: String, service: String): UIO[Double] =
    Metric.gauge(name).tagged(Set(MetricLabel("service", service), MetricLabel("listener", CertificateMetrics.MutualTlsListener))).value.map(_.value)

  private def pemFile(chain: String) =
    ZIO.attemptBlocking:
      val file = Files.createTempFile("listener-", ".crt")
      Files.writeString(file, chain)
      file.toString

  def spec = suite("CertificateMetrics")(
    test("a listener's certificate is published with its end and the length of its validity") {
      val leaf = TestCertificates.generate(subject = "CN=central.example.com", validity = Some((Date.from(notBefore), Date.from(notAfter))))
      for
        path <- pemFile(leaf.certificatePem)
        _ <- CertificateMetrics.listenerFromFile("svc-leaf", CertificateMetrics.MutualTlsListener, path)
        end <- gauge("listener_certificate_not_after_timestamp_seconds", "svc-leaf")
        life <- gauge("listener_certificate_lifetime_seconds", "svc-leaf")
      yield assertTrue(end == notAfter.getEpochSecond.toDouble, life == (notAfter.getEpochSecond - notBefore.getEpochSecond).toDouble)
    },
    test("the leaf is the first certificate of a chain, not the issuer after it") {
      val root = TestCertificates.generate(
        subject = "CN=Root",
        ca = true,
        validity = Some((Date.from(notBefore), Date.from(notAfter.plusSeconds(86400L * 3650)))),
      )
      val leaf = TestCertificates.generate(subject = "CN=leaf", issuer = Some(root), validity = Some((Date.from(notBefore), Date.from(notAfter))))
      for
        path <- pemFile(leaf.certificatePem + "\n" + root.certificatePem)
        _ <- CertificateMetrics.listenerFromFile("svc-chain", CertificateMetrics.MutualTlsListener, path)
        end <- gauge("listener_certificate_not_after_timestamp_seconds", "svc-chain")
      yield assertTrue(end == notAfter.getEpochSecond.toDouble)
    },
    test("a file that is missing or is no certificate sets nothing and does not fail") {
      for
        garbage <- pemFile("not a certificate")
        missing <- CertificateMetrics.listenerFromFile("svc-missing", CertificateMetrics.MutualTlsListener, "/nonexistent/server.crt").exit
        unreadable <- CertificateMetrics.listenerFromFile("svc-garbage", CertificateMetrics.MutualTlsListener, garbage).exit
        a <- gauge("listener_certificate_not_after_timestamp_seconds", "svc-missing")
        b <- gauge("listener_certificate_not_after_timestamp_seconds", "svc-garbage")
      yield assertTrue(missing.isSuccess, unreadable.isSuccess, a == 0.0, b == 0.0)
    },
  )
