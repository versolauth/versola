package versola.oauth.jwks

import versola.util.{CoreConfig, JWT, ReloadingCache}
import zio.{Schedule, Scope, UIO, ZIO, ZLayer}

/** Provides the JWKS synced from central. Signing reads the active key's id and
  * algorithm from here, and verification uses the full key set.
  */
trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]

object JwksService:
  def live: ZLayer[JwksSyncClient & Scope & CoreConfig, Throwable, JwksService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CoreConfig](config =>
        ReloadingCache.make[JWT.PublicKeys](config.configurationCacheRefreshInterval),
      )
    ) >>>
      ZLayer.fromFunction(Impl(_))

  case class Impl(
      cache: ReloadingCache[JWT.PublicKeys],
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = cache.get
