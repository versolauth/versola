package versola.util

import org.apache.commons.codec.digest.Blake3
import zio.Duration

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** RFC 9449 §8/§9: the wire format of a server-provided `DPoP-Nonce`, shared by the
  * authorization server and the resource server.
  *
  * A nonce is `<issuedAtSeconds>.<mac>`, where `mac` authenticates `issuedAtSeconds` under a
  * secret held only by the issuer -- the same signed-value shape used for cookies elsewhere.
  * Validity needs no lookup, only recomputing the MAC and checking the TTL, which is what lets
  * every replica of a service accept a nonce any other replica issued without sharing state.
  *
  * §9 treats the two nonce spaces as separate: a resource server issues its own nonces under
  * its own secret, so a nonce minted by `auth` is not valid at `edge` and vice versa. Only the
  * format is common, which is why it lives here rather than in either service.
  */
object DpopNonce:

  enum Error:
    case Malformed
    case Expired

  private val Separator = '.'

  def issue(secret: Secret.Bytes32, issuedAt: Instant): String =
    val issuedAtSeconds = issuedAt.getEpochSecond
    s"$issuedAtSeconds$Separator${Base64.urlEncode(mac(secret, issuedAtSeconds))}"

  /** Accepts a nonce this issuer minted no longer than `ttl` ago. */
  def verify(
      secret: Secret.Bytes32,
      nonce: String,
      now: Instant,
      ttl: Duration,
  ): Either[Error, Unit] =
    nonce.split(Separator) match
      case Array(issuedAtStr, sigB64) =>
        for
          issuedAtSeconds <- issuedAtStr.toLongOption.toRight(Error.Malformed)
          sigBytes <- decode(sigB64)
          _ <- Either.cond(
            MessageDigest.isEqual(mac(secret, issuedAtSeconds), sigBytes),
            (),
            Error.Malformed,
          )
          // Only past the MAC check is the value one this server wrote from its own clock, and
          // so within `Instant`'s range. Built before the check, an unsigned timestamp like
          // `Long.MaxValue` would parse and then throw out of here rather than be rejected.
          issuedAt = Instant.ofEpochSecond(issuedAtSeconds)
          _ <- Either.cond(
            !issuedAt.isAfter(now) && !issuedAt.isBefore(now.minus(ttl)),
            (),
            Error.Expired,
          )
        yield ()
      case _ =>
        Left(Error.Malformed)

  private def decode(value: String): Either[Error, Array[Byte]] =
    try Right(Base64.urlDecode(value))
    catch case _: IllegalArgumentException => Left(Error.Malformed)

  private def mac(secret: Secret.Bytes32, issuedAtSeconds: Long): Array[Byte] =
    val digest = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(secret)
      .update(issuedAtSeconds.toString.getBytes(StandardCharsets.UTF_8))
      .doFinalize(digest)
    digest
