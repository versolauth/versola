package versola.central.configuration.clients.certificates

import versola.central.audit.CentralAudit
import versola.central.audit.CentralAudit.CertReasons
import versola.central.configuration.clients.ClientCertificateAuthority
import versola.util.audit.{AuditOutcome, AuditSeverity}
import zio.test.*

import java.net.ConnectException
import java.time.Instant

object ClientCertificateIssuerSpec extends ZIOSpecDefault:

  def spec = suite("ClientCertificateIssuer.reasonOf")(
    test("a CA that cannot be asked, or has expired, is told apart from one that answered") {
      assertTrue(
        ClientCertificateIssuer.reasonOf(ClientCertificateIssuer.NotConfigured) == CertReasons.CaNotConfigured,
        ClientCertificateIssuer.reasonOf(ClientCertificateAuthority.Expired(Instant.parse("2026-01-01T00:00:00Z"))) == CertReasons.CaExpired,
      )
    },
    test("what a backend raised is the reason it named, never its message") {
      val failures = List(
        ClientCertificateIssuer.IssuerFailed.unreachable(ConnectException("refused: ca.internal")) -> CertReasons.IssuerUnreachable,
        ClientCertificateIssuer.IssuerFailed.refused("400: body") -> CertReasons.IssuerRefused,
        ClientCertificateIssuer.IssuerFailed.timedOut("60 seconds") -> CertReasons.IssuerTimeout,
        ClientCertificateIssuer.IssuerFailed.invalidAnswer("<html>") -> CertReasons.IssuerInvalidAnswer,
      )
      assertTrue(
        failures.forall((failure, reason) => ClientCertificateIssuer.reasonOf(failure) == reason),
        failures.forall((failure, _) => !ClientCertificateIssuer.reasonOf(failure).contains(" ")),
      )
    },
    test("any other failure is issuer_error, and every reason is in the closed vocabulary") {
      val reasons = List(
        RuntimeException("boom"),
        IllegalStateException("the request's signature does not verify"),
        ClientCertificateIssuer.NotConfigured,
        ClientCertificateIssuer.IssuerFailed.refused("x"),
      ).map(ClientCertificateIssuer.reasonOf)
      assertTrue(
        ClientCertificateIssuer.reasonOf(RuntimeException("boom")) == CertReasons.IssuerError,
        reasons.forall(CertReasons.all.contains),
      )
    },
    test("a denied or failed certificate event is a notice: more than an ordinary one, less than a revocation") {
      assertTrue(
        CentralAudit.catalog.severity(CentralAudit.CertRenewed, AuditOutcome.failure) == AuditSeverity.notice,
        CentralAudit.catalog.severity(CentralAudit.CertEnrolled, AuditOutcome.denied) == AuditSeverity.notice,
        CentralAudit.catalog.severity(CentralAudit.CertRenewed, AuditOutcome.success) == AuditSeverity.info,
        CentralAudit.catalog.severity(CentralAudit.CertRevoked, AuditOutcome.success) == AuditSeverity.high,
      )
    },
  )
