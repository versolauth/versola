package versola.edge

import versola.edge.model.{Resource, ResourceId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZIO, ZLayer}

trait ResourceService:
  def findByResourceId(resourceId: ResourceId): UIO[Option[Resource]]

object ResourceService:
  def live: ZLayer[ResourcesSyncClient & Scope & EdgeConfig, Throwable, ResourceService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[EdgeConfig](config =>
        ReloadingCache.make[Map[ResourceId, Resource]](config.configurationCacheRefreshInterval),
      )
    ) >>>
      ZLayer.fromFunction(Impl(_))

  class Impl(cache: ReloadingCache[Map[ResourceId, Resource]]) extends ResourceService:
    override def findByResourceId(resourceId: ResourceId): UIO[Option[Resource]] =
      cache.get.map(_.get(resourceId))
