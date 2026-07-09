package versola.edge

import versola.edge.model.{Resource, ResourceId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZLayer}

trait ResourceService:
  def findByResourceId(resourceId: ResourceId): UIO[Option[Resource]]

object ResourceService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[ResourcesSyncClient & Scope, Throwable, ResourceService] =
    ZLayer(ReloadingCache.make[Map[ResourceId, Resource]](schedule)) >>>
      ZLayer.fromFunction(Impl(_))

  class Impl(cache: ReloadingCache[Map[ResourceId, Resource]]) extends ResourceService:
    override def findByResourceId(resourceId: ResourceId): UIO[Option[Resource]] =
      cache.get.map(_.get(resourceId))
