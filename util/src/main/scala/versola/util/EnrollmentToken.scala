package versola.util

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.util.Try

/** What an edge is started with (#962): one string, `vtk1.<base64url(enrollUrl)>.<secret>`.
  *
  * `enrollUrl` is where central takes enrollments (`central.public-urls.enroll`; the edge posts to `<enrollUrl>/edge/enroll`), so the
  * edge needs nothing else to find central; `secret` is 32 random bytes, base64url, and the only part central keeps (as a hash, of the
  * whole string) and resolves the edge from. Single use, 24 hours for a partner's edge.
  *
  * Central builds it ([[encode]]), the edge takes it apart ([[parse]]); the edge refuses an address that is not `https`, since the
  * token is sent there.
  */
object EnrollmentToken:
  val Prefix: String = "vtk1"

  /** The pieces of a token: where to enrol, and the secret to enrol with (the whole token is what is sent). */
  case class Parsed(enrollUrl: String, secret: String)

  def encode(enrollUrl: String, secret: String): String =
    s"$Prefix.${Base64Url.encode(enrollUrl.getBytes(StandardCharsets.UTF_8))}.$secret"

  def parse(token: String): Either[String, Parsed] =
    token.trim.split('.') match
      case Array(Prefix, address, secret) if secret.nonEmpty =>
        for
          url <- Try(Base64Url.decodeStr(address)).toEither.left.map(_ => "the address in the token is not base64url")
          uri <- Try(URI.create(url)).toEither.left.map(_ => "the address in the token is not a URL")
          _ <- Either.cond(
            uri.getScheme == "https" && uri.getHost != null && uri.getUserInfo == null,
            (),
            "the address in the token must be an https URL",
          )
        yield Parsed(url, secret)
      case Array(Prefix, _*) => Left("the token is malformed")
      case _ => Left(s"the token does not start with '$Prefix.'")
