package versola.oauth.dpop

import org.apache.commons.codec.digest.Blake3
import versola.util.{Base64, CoreConfig}
import zio.{Clock, IO, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** RFC 9449 \u00a78: server-provided nonces a client must echo back in a fresh proof, so a proof
  * captured once can't be replayed indefinitely -- each use requires a new, server-attested
  * `iat`/signature.
  *
  * Stateless: a nonce is `<issuedAtSeconds>.<mac>`, where `mac` authenticates
  * `issuedAtSeconds` under [[CoreConfig.Security.dpopNoncesSecret]] -- the same signed-cookie
  * shape used elsewhere (see `versola.oauth.model.cookies`). Validity needs no lookup, only
  * recomputing the MAC and checking [[CoreConfig.DpopConfig.nonceTtl]] hasn't elapsed.
  */
trait DpopNonceService:
  def issue: UIO[String]
  def verify(nonce: String, now: Instant): IO[DpopNonceService.Error, Unit]

object DpopNonceService:
  enum Error:
    case Malformed
    case Expired

  def live: ZLayer[CoreConfig, Nothing, DpopNonceService] =
    ZLayer.fromFunction(Impl(_))

  private val Separator = '.'

  class Impl(config: CoreConfig) extends DpopNonceService:

    override def issue: UIO[String] =
      Clock.instant.map(encode)

    override def verify(nonce: String, now: Instant): IO[Error, Unit] =
      nonce.split(Separator) match
        case Array(issuedAtStr, sigB64) =>
          for
            issuedAtSeconds <- ZIO.attempt(issuedAtStr.toLong).orElseFail(Error.Malformed)
            sigBytes <- ZIO.attempt(Base64.urlDecode(sigB64)).orElseFail(Error.Malformed)
            _ <- ZIO.fail(Error.Malformed)
              .unless(MessageDigest.isEqual(mac(issuedAtSeconds), sigBytes))
            // Only past the MAC check is the value one this server wrote from its own clock,
            // and so within `Instant`'s range. Built before the check, an unsigned timestamp
            // like `Long.MaxValue` would parse and then throw out of here as a defect instead
            // of being rejected as malformed.
            issuedAt = Instant.ofEpochSecond(issuedAtSeconds)
            _ <- ZIO.fail(Error.Expired)
              .unless(!issuedAt.isAfter(now) && !issuedAt.isBefore(now.minus(config.dpopOrDefault.nonceTtl)))
          yield ()
        case _ =>
          ZIO.fail(Error.Malformed)

    private def encode(issuedAt: Instant): String =
      val issuedAtSeconds = issuedAt.getEpochSecond
      s"$issuedAtSeconds$Separator${Base64.urlEncode(mac(issuedAtSeconds))}"

    private def mac(issuedAtSeconds: Long): Array[Byte] =
      val digest = Array.ofDim[Byte](32)
      Blake3.initKeyedHash(config.security.dpopNoncesSecret)
        .update(issuedAtSeconds.toString.getBytes(StandardCharsets.UTF_8))
        .doFinalize(digest)
      digest
