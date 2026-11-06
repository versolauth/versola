package versola.oauth.est

import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.*

import java.security.cert.X509Certificate

/** EST (RFC 7030) under `/.well-known/est/`: a client registered with `estEnrollment` gets and renews its mTLS client
  * certificate here, which central issues.
  *
  * Which listener serves what is the security design. `simpleenroll` is on the main listener, where a client with no
  * certificate can reach it, and is authenticated by a one-time token (HTTP Basic or Bearer, §3.2.3). `simplereenroll` is
  * on the mutual-TLS listener alone ([[reenrollmentRoutes]]) and is authenticated by the certificate the client presented
  * in the handshake, which the listener validated against `mutual-tls.trusted-certificates`: a certificate in a header is
  * never read here, so nothing that can only speak HTTP can renew. `cacerts` and `csrattrs` are on both.
  */
object EstController extends Controller:
  type Env = Tracing & EstService

  /** Served on the main listener. */
  def routes: Routes[Env, Throwable] = Routes(cacertsEndpoint, csrattrsEndpoint, simpleEnrollEndpoint)

  /** Served on the mutual-TLS listener (RFC 8705 §5's, `MPORT`). */
  def reenrollmentRoutes: Routes[Env, Throwable] = Routes(cacertsEndpoint, csrattrsEndpoint, simpleReenrollEndpoint)

  /** A request is a PKCS#10 of a few hundred bytes; this bounds what an unauthenticated caller can make auth read (the body
    * is read through a stream that stops one byte past it). */
  val MaxBody: Int = 16 * 1024

  private val PkcsMime = "application/pkcs7-mime; smime-type=certs-only"

  val cacertsEndpoint =
    Method.GET / ".well-known" / "est" / "cacerts" -> handler { (_: Request) =>
      handled:
        ZIO.serviceWithZIO[EstService](_.caCertificates).map(certsOnly(_, None))
    }

  /** RFC 7030 §4.5.2: 204 says there are no attributes to ask the client to put in its request. The subject is told where
    * the client can act on it, with the one-time token and in the refusal of a request that says another. */
  val csrattrsEndpoint =
    Method.GET / ".well-known" / "est" / "csrattrs" -> handler { (_: Request) =>
      handled:
        ZIO.serviceWithZIO[EstService](_.caCertificates).as(Response.status(Status.NoContent))
    }

  val simpleEnrollEndpoint =
    Method.POST / ".well-known" / "est" / "simpleenroll" -> handler { (request: Request) =>
      handled:
        for
          credentials <- ZIO.fromOption(credentialsOf(request)).orElseFail(EstError.Unauthorized)
          body <- bodyOf(request)
          issued <- ZIO.serviceWithZIO[EstService](_.enroll(credentials, body))
        yield certsOnly(issued.pkcs7, issued.chainEndsAt)
    }

  val simpleReenrollEndpoint =
    Method.POST / ".well-known" / "est" / "simplereenroll" -> handler { (request: Request) =>
      handled:
        for
          presented <- ZIO.fromOption(request.remoteCertificate.collect { case certificate: X509Certificate => certificate })
            .orElseFail(EstError.Unauthorized)
          body <- bodyOf(request)
          issued <- ZIO.serviceWithZIO[EstService](_.reenroll(presented, body))
        yield certsOnly(issued.pkcs7, issued.chainEndsAt)
    }

  private def credentialsOf(request: Request): Option[EstService.Credentials] =
    request.header(Header.Authorization).flatMap:
      case Header.Authorization.Basic(username, password) =>
        Some(EstService.Credentials(Some(username).filter(_.nonEmpty), password.stringValue))
      case Header.Authorization.Bearer(token) => Some(EstService.Credentials(None, token.stringValue))
      case _ => None

  /** §4.2.1: `application/pkcs10`, and no more than a request's worth of it. */
  private def bodyOf(request: Request): IO[EstError | Throwable, String] =
    for
      _ <- ZIO.fail(EstError.BadRequest("the request is application/pkcs10"))
        .unless(request.header(Header.ContentType).exists(_.mediaType.fullType == "application/pkcs10"))
      declared = request.header(Header.ContentLength).map(_.length)
      _ <- ZIO.fail(EstError.BadRequest("the request is too large")).when(declared.exists(_ > MaxBody))
      // Read no more than a request's worth even where the length is not declared (chunked) or understated.
      bytes <- request.body.asStream.take(MaxBody + 1L).runCollect
      _ <- ZIO.fail(EstError.BadRequest("the request is too large")).when(bytes.length > MaxBody)
    yield String(bytes.toArray, java.nio.charset.StandardCharsets.US_ASCII)

  private def certsOnly(base64: Array[Byte], chainEndsAt: Option[java.time.Instant]): Response =
    val headers = Headers(
      Header.Custom("Content-Type", PkcsMime),
      Header.Custom("Content-Transfer-Encoding", "base64"),
    ) ++ chainEndsAt.fold(Headers.empty)(at => Headers(Header.Custom("Versola-Chain-Ends-At", at.toString)))
    Response(status = Status.Ok, headers = headers, body = Body.fromArray(base64))

  /** Failures as RFC 7030 §4.2.3 has them: a 4xx with a plain-text reason, 401 asking for HTTP authentication. */
  private def handled(effect: ZIO[EstService, EstError | Throwable, Response]): ZIO[EstService, Throwable, Response] =
    effect.catchAll:
      case EstError.Disabled => ZIO.succeed(Response.status(Status.NotFound))
      case EstError.Unauthorized =>
        Observability.setError("est_unauthorized") *>
          ZIO.succeed(Response.status(Status.Unauthorized).addHeader(Header.Custom("WWW-Authenticate", """Basic realm="est"""")))
      case EstError.TooManyRequests =>
        Observability.setError("est_rate_limited") *>
          ZIO.succeed(Response.status(Status.TooManyRequests).addHeader(Header.Custom("Retry-After", "60")))
      case EstError.BadRequest(reason) =>
        Observability.setError("est_bad_request", Some(reason)) *> ZIO.succeed(Response.text(reason).status(Status.BadRequest))
      case EstError.Forbidden(reason) =>
        Observability.setError("est_forbidden", Some(reason)) *> ZIO.succeed(Response.text(reason).status(Status.Forbidden))
      case error: Throwable => ZIO.fail(error)
