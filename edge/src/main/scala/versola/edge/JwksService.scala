package versola.edge

import versola.util.{JWT, ReloadingCache}
import zio.{Schedule, Scope, UIO, ZIO, ZLayer}

trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]

object JwksService:
  def live: ZLayer[JwksSyncClient & Scope & EdgeConfig, Throwable, JwksService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[EdgeConfig](config =>
        ReloadingCache.make[JWT.PublicKeys](Schedule.spaced(config.configurationCacheRefreshInterval)),
      )
    ) >>>
      ZLayer.fromFunction(Impl(_))

  case class Impl(
      cache: ReloadingCache[JWT.PublicKeys],
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = cache.get
