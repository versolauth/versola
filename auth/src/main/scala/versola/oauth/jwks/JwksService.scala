package versola.oauth.jwks

import versola.util.{JWT, ReloadingCache}
import zio.{Schedule, Scope, UIO, ZLayer}

/** Provides the JWKS synced from central. Signing reads the active key's id and
  * algorithm from here, and verification uses the full key set.
  */
trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]

object JwksService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[JwksSyncClient & Scope, Throwable, JwksService] =
    ZLayer(ReloadingCache.make[JWT.PublicKeys](schedule)) >>>
      ZLayer.fromFunction(Impl(_))

  case class Impl(
      cache: ReloadingCache[JWT.PublicKeys],
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = cache.get
