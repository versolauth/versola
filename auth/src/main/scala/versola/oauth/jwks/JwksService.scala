package versola.oauth.jwks

import com.nimbusds.jose.jwk.{RSAKey as JwkRsaKey}
import versola.util.{CacheSource, CoreConfig, JWT, ReloadingCache}
import zio.{Scope, Task, UIO, ZIO, ZLayer}

import java.security.PrivateKey
import java.security.interfaces.RSAKey as JavaRsaKey
import scala.jdk.CollectionConverters.*

/** Provides the JWKS synced from central, plus this instance's own signing key.
  *
  * Verification uses the full key set ([[getPublicKeys]]). Signing must not use
  * whichever entry central currently reports as [[JWT.PublicKeys.active]] -- that's
  * just the first element of an unordered list, and it can drift ahead of this
  * instance's own static private key across a rotation, producing tokens whose kid
  * doesn't match the key that actually signed them (see #104). Instead
  * [[signingKey]] picks, out of the synced set, the one entry whose public modulus
  * matches this instance's configured private key -- the only kid it can actually
  * sign with, regardless of rotation state elsewhere.
  */
trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]
  def signingKey: UIO[Option[JWT.PublicKey]]

object JwksService:
  case class Snapshot(publicKeys: JWT.PublicKeys, signingKey: Option[JWT.PublicKey])

  /** The JWKS entry whose modulus matches `privateKey`'s, i.e. the one this instance
    * can actually sign with. During a rotation window central's JWKS holds both the
    * outgoing and incoming key; this always resolves to the one whose private half
    * this instance actually has, never to whichever one happens to be listed first.
    */
  private[jwks] def resolveSigningKey(privateKey: PrivateKey, publicKeys: JWT.PublicKeys): Option[JWT.PublicKey] =
    privateKey match
      case rsaPrivateKey: JavaRsaKey =>
        publicKeys.keys.getKeys.asScala.collectFirst {
          case key: JwkRsaKey if key.toRSAPublicKey.getModulus == rsaPrivateKey.getModulus =>
            JWT.PublicKey(key)
        }
      case _ => None

  /** Wraps the raw JWKS sync with the derived signing key, so the match against this
    * instance's private key is computed once per cache refresh instead of once per
    * JWT signed.
    */
  private class SigningAwareSource(client: JwksSyncClient, privateKey: PrivateKey) extends CacheSource[Snapshot]:
    override def getAll: Task[Snapshot] =
      client.getAll.map(keys => Snapshot(keys, resolveSigningKey(privateKey, keys)))

  def live: ZLayer[JwksSyncClient & Scope & CoreConfig, Throwable, JwksService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CoreConfig] { config =>
        ZIO.serviceWithZIO[JwksSyncClient] { client =>
          ReloadingCache.make[Snapshot](config.configurationCacheRefreshInterval)
            .provideSome[Scope](ZLayer.succeed(new SigningAwareSource(client, config.jwt.privateKey): CacheSource[Snapshot]))
        }
      }
    ) >>>
      ZLayer.fromFunction(Impl(_))

  case class Impl(
      cache: ReloadingCache[Snapshot],
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = cache.get.map(_.publicKeys)
    override def signingKey: UIO[Option[JWT.PublicKey]] = cache.get.map(_.signingKey)
