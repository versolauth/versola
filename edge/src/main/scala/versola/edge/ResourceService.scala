package versola.edge

import versola.edge.model.Resource
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZLayer}

trait ResourceService:
  def findByAlias(alias: String): UIO[Option[Resource]]

object ResourceService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[ResourcesSyncClient & Scope, Throwable, ResourceService] =
    ZLayer(ReloadingCache.make[Map[String, Resource]](schedule)) >>>
      ZLayer.fromFunction(Impl(_))

  class Impl(cache: ReloadingCache[Map[String, Resource]]) extends ResourceService:
    override def findByAlias(alias: String): UIO[Option[Resource]] =
      cache.get.map(_.get(alias))
