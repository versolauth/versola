package versola.oauth.est

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

  val live: ZLayer[CoreConfig & EstCentralClient, Throwable, EstService] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CoreConfig]
        central <- ZIO.service[EstCentralClient]
        // Read once, at startup: an EST that cannot say whom to trust is a deployment error, not a request's.
        ca <- config.est.filter(_.enabled) match
          case None => ZIO.succeed(None)
          case Some(est) =>
            val path = est.caCertificates.orElse(config.mutualTls.map(_.trustedCertificates))
              .getOrElse(throw IllegalStateException("est is enabled but names no CA to hand out: set est.ca-certificates or mutual-tls"))
            ZIO.attemptBlocking(Files.readString(Path.of(path))).flatMap: pem =>
              ZIO.fromTry(EstCodec.certificates(pem)).filterOrFail(_.nonEmpty)(IllegalStateException(s"no certificate in $path"))
                .map(certificates => Some(EstCodec.certsOnly(certificates)))
      yield Impl(ca, central)

  class Impl(ca: Option[Array[Byte]], central: EstCentralClient) extends EstService:

    override def caCertificates: IO[EstError, Array[Byte]] =
      ZIO.fromOption(ca).orElseFail(EstError.Disabled)

    override def enroll(credentials: Credentials, body: String): IO[EstError | Throwable, Issued] =
      counted("enroll"):
        for
          _ <- caCertificates
          csr <- ZIO.fromEither(EstCodec.requestPem(body)).mapError(EstError.BadRequest(_))
          issued <- central.enroll(credentials.token, credentials.clientId, csr)
          result <- respond(issued)
        yield result

    override def reenroll(presented: X509Certificate, body: String): IO[EstError | Throwable, Issued] =
      counted("reenroll"):
        for
          _ <- caCertificates
          now <- Clock.instant
          // The handshake validated the chain; one that has expired, or is not valid yet, is not renewed with itself (an
          // operator enrols the client again).
          _ <- ZIO.fail(EstError.Unauthorized).when(now.isBefore(presented.getNotBefore.toInstant) || !now.isBefore(presented.getNotAfter.toInstant))
          csr <- ZIO.fromEither(EstCodec.requestPem(body)).mapError(EstError.BadRequest(_))
          issued <- central.renew(CertificateSubject.pem("CERTIFICATE", presented.getEncoded), csr)
          result <- respond(issued)
        yield result

    private def respond(issued: EstCentralClient.Issued): IO[Throwable, Issued] =
      ZIO.fromTry(EstCodec.certificates(issued.certificate)).map(certificates => Issued(EstCodec.certsOnly(certificates), issued.chainEndsAt))

    private def counted[A](operation: String)(effect: IO[EstError | Throwable, A]): IO[EstError | Throwable, A] =
      effect.tapBoth(
        {
          case EstError.Disabled => ZIO.unit
          case EstError.Unauthorized => count(operation, "unauthorized")
          case _: EstError.BadRequest => count(operation, "bad_request")
          case _: EstError.Forbidden => count(operation, "forbidden")
          case _ => count(operation, "error")
        },
        _ => count(operation, "issued"),
      )
