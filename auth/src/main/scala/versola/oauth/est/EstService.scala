package versola.oauth.est

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.{AuthActors, AuthAuditKinds}
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.{CertificateSubject, CoreConfig}
import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.nio.file.{Files, Path}
import java.security.cert.X509Certificate
import java.time.Instant

/** EST (RFC 7030) as auth serves it: a client registered with `estEnrollment` gets and renews its mTLS client certificate.
  *
  * Auth checks what it can see -- that the request is a PKCS#10, and for a re-enrolment that the certificate presented on
  * its own mutual-TLS listener is valid now -- and has central decide and issue. It holds no CA credentials, so what it
  * can be made to do is ask. A request that is wrong for the client (another subject, a weak or reused key) is central's
  * to refuse, and arrives here as [[EstError.BadRequest]].
  */
trait EstService:
  /** `cacerts` (§4.1): the CA certificates a client must trust for what is issued to it, as a base64 certs-only PKCS#7. */
  def caCertificates: IO[EstError, Array[Byte]]

  /** `simpleenroll` (§4.2.1): the first certificate, for the one-time token in `credentials`. */
  def enroll(credentials: EstService.Credentials, body: String): IO[EstError | Throwable, EstService.Issued]

  /** `simplereenroll` (§4.2.2): a new certificate for the client that presented `presented` in the TLS handshake. */
  def reenroll(presented: X509Certificate, body: String): IO[EstError | Throwable, EstService.Issued]

object EstService:
  /** The token of HTTP Basic (the user name being the client's id) or Bearer (no user name). */
  case class Credentials(clientId: Option[String], token: String)

  /** A certs-only PKCS#7 (base64) and where the client's chain of renewals ends, if it does. */
  case class Issued(pkcs7: Array[Byte], chainEndsAt: Option[Instant])

  private val requests = Metric.counter("est_requests_total")

  private def count(operation: String, outcome: String): UIO[Unit] =
    requests.tagged(Set(MetricLabel("operation", operation), MetricLabel("outcome", outcome))).increment

  val live: ZLayer[CoreConfig & EstCentralClient & AuditEvents, Throwable, EstService] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CoreConfig]
        central <- ZIO.service[EstCentralClient]
        audit <- ZIO.service[AuditEvents]
        // Read once, at startup: an EST that cannot say whom to trust is a deployment error, not a request's. The CA is
        // named, never inferred: `mutual-tls.trusted-certificates` is every anchor a client chain is validated against and
        // may hold others (the CA of edges), which a bank must not be told to trust as its own.
        ca <- config.est.filter(_.enabled) match
          case None => ZIO.succeed(None)
          case Some(est) =>
            for
              path <- ZIO.fromOption(est.caCertificates).orElseFail(
                IllegalStateException("est is enabled but names no CA to hand out: set est.ca-certificates to a file with the client CA alone"),
              )
              _ <- ZIO.fail(IllegalStateException("est is enabled but there is no mutual-tls block: re-enrolment is served on that listener only"))
                .when(config.mutualTls.isEmpty)
              pem <- ZIO.attemptBlocking(Files.readString(Path.of(path)))
              certificates <- ZIO.fromTry(EstCodec.certificates(pem)).filterOrFail(_.nonEmpty)(IllegalStateException(s"no certificate in $path"))
            yield Some(EstCodec.certsOnly(certificates))
        limiter <- AttemptLimiter.make(EnrollAttempts, 1.minute)
      yield Impl(ca, central, limiter, audit)

  /** Attempts at `simpleenroll` per client id and minute: an operator's token is tried once or twice, not dozens of times. */
  val EnrollAttempts = 30

  class Impl(ca: Option[Array[Byte]], central: EstCentralClient, limiter: AttemptLimiter, audit: AuditEvents) extends EstService:

    override def caCertificates: IO[EstError, Array[Byte]] =
      ZIO.fromOption(ca).orElseFail(EstError.Disabled)

    override def enroll(credentials: Credentials, body: String): IO[EstError | Throwable, Issued] =
      counted("enroll", credentials.clientId):
        for
          _ <- caCertificates
          admitted <- limiter.admit(credentials.clientId.getOrElse(""))
          _ <- ZIO.fail(EstError.TooManyRequests).unless(admitted)
          csr <- request(body)
          issued <- central.enroll(credentials.token, credentials.clientId, csr)
          result <- respond(issued)
          _ <- auditIssued("enroll", AuthAuditKinds.CertEnrolled, credentials.clientId, issued)
        yield result

    override def reenroll(presented: X509Certificate, body: String): IO[EstError | Throwable, Issued] =
      counted("reenroll", CertificateSubject.commonNameOf(presented.getSubjectX500Principal)):
        for
          _ <- caCertificates
          now <- Clock.instant
          // The handshake validated the chain; one that has expired, or is not valid yet, is not renewed with itself (an
          // operator enrols the client again).
          _ <- ZIO.fail(EstError.Unauthorized).when(now.isBefore(presented.getNotBefore.toInstant) || !now.isBefore(presented.getNotAfter.toInstant))
          csr <- request(body)
          issued <- central.renew(CertificateSubject.pem("CERTIFICATE", presented.getEncoded), csr)
          result <- respond(issued)
          _ <- auditIssued("reenroll", AuthAuditKinds.CertRenewed, CertificateSubject.commonNameOf(presented.getSubjectX500Principal), issued)
        yield result

    /** A PKCS#10 asking for a key worth issuing for, before central is asked: what an unauthenticated caller sends costs
      * auth a parse, not central a request. */
    private def request(body: String): IO[EstError, String] =
      for
        csr <- ZIO.fromEither(EstCodec.requestPem(body)).mapError(EstError.BadRequest(_))
        _ <- ZIO.fromEither(CertificateSubject.requestedKey(csr)).mapError(EstError.BadRequest(_))
      yield csr

    private def respond(issued: EstCentralClient.Issued): IO[Throwable, Issued] =
      ZIO.fromTry(EstCodec.certificates(issued.certificate)).map(certificates => Issued(EstCodec.certsOnly(certificates), issued.chainEndsAt))

    /** `cert.enrolled` / `cert.renewed`: the client is the actor; `serial` and `notAfter` are of the certificate central issued. */
    private def auditIssued(
        operation: String,
        kind: versola.util.audit.AuditKind,
        clientId: Option[String],
        issued: EstCentralClient.Issued,
    ): IO[Throwable, Unit] =
      val leaf = EstCodec.certificates(issued.certificate).toOption.flatMap(_.headOption)
      audit.log(
        AuditDraft(
          kind = kind,
          outcome = AuditOutcome.success,
          actor = Some((AuthActors.client, clientId)),
          details = AuthAuditDetails.Cert(
            operation = operation,
            clientId = clientId,
            serial = leaf.map(_.getSerialNumber.toString(16)),
            notAfter = leaf.map(_.getNotAfter.toInstant.toString),
          ),
        ),
      )

    /** What a refused request is called and how it counts in the audit log: a caller that could not prove who it is, or may not, is
      * `denied`; a request that was throttled or malformed is `failure`. A refusal that is the server's own (central down) is not an event. */
    private def auditRefused(operation: String, clientId: Option[String], error: EstError): IO[Throwable, Unit] =
      val classified = error match
        case EstError.Unauthorized => Some((AuditOutcome.denied, "est_unauthorized"))
        case _: EstError.Forbidden => Some((AuditOutcome.denied, "est_forbidden"))
        case EstError.TooManyRequests => Some((AuditOutcome.failure, "est_rate_limited"))
        case _: EstError.BadRequest => Some((AuditOutcome.failure, "est_bad_request"))
        case _ => None
      ZIO.foreachDiscard(classified): (outcome, reason) =>
        audit.log(
          AuditDraft(
            kind = if operation == "enroll" then AuthAuditKinds.CertEnrolled else AuthAuditKinds.CertRenewed,
            outcome = outcome,
            reason = Some(reason),
            actor = Some((AuthActors.client, clientId)),
            details = AuthAuditDetails.Cert(operation = operation, clientId = clientId),
          ),
        )

    private def counted[A](operation: String, clientId: Option[String])(effect: IO[EstError | Throwable, A]): IO[EstError | Throwable, A] =
      effect.tapBoth(
        {
          case EstError.Disabled => ZIO.unit
          case EstError.Unauthorized => count(operation, "unauthorized") *> auditRefused(operation, clientId, EstError.Unauthorized)
          case EstError.TooManyRequests => count(operation, "rate_limited") *> auditRefused(operation, clientId, EstError.TooManyRequests)
          case error: EstError.BadRequest => count(operation, "bad_request") *> auditRefused(operation, clientId, error)
          case error: EstError.Forbidden => count(operation, "forbidden") *> auditRefused(operation, clientId, error)
          case _ => count(operation, "error")
        },
        _ => count(operation, "issued"),
      )
