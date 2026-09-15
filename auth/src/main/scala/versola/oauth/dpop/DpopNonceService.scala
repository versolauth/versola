package versola.oauth.dpop

import versola.util.{CoreConfig, DpopNonce}
import zio.{Clock, IO, UIO, ZIO, ZLayer}

import java.time.Instant

/** RFC 9449 §8: server-provided nonces a client must echo back in a fresh proof, so a proof
  * captured once can't be replayed indefinitely -- each use requires a new, server-attested
  * `iat`/signature.
  *
  * Stateless, so any replica accepts a nonce any other replica issued: see [[DpopNonce]] for
  * the format. This is the authorization server's nonce space, keyed by
  * [[CoreConfig.Security.dpopNoncesSecret]]; the resource server has its own (§9).
  */
trait DpopNonceService:
  def issue: UIO[String]
  def verify(nonce: String, now: Instant): IO[DpopNonce.Error, Unit]

object DpopNonceService:
  def live: ZLayer[CoreConfig, Nothing, DpopNonceService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(config: CoreConfig) extends DpopNonceService:

    override def issue: UIO[String] =
      Clock.instant.map(DpopNonce.issue(config.security.dpopNoncesSecret, _))

    override def verify(nonce: String, now: Instant): IO[DpopNonce.Error, Unit] =
      ZIO.fromEither(
        DpopNonce.verify(
          secret = config.security.dpopNoncesSecret,
          nonce = nonce,
          now = now,
          ttl = config.dpopOrDefault.nonceTtl,
        ),
      )
