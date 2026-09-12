package versola.edge

import versola.edge.model.{Resource, ResourceId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait ResourceService:
  def findByResourceId(resourceId: ResourceId): UIO[Option[Resource]]

  /** Reloads the cache from central now, instead of waiting for `configurationCacheRefreshInterval`.
    * Backs the non-prod `/service/configuration/sync` endpoint; nothing in request handling
    * calls this. */
  def refreshNow: Task[Unit]

object ResourceService:
  def live: ZLayer[ResourcesSyncClient & Scope & EdgeConfig, Throwable, ResourceService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[ResourceId, Resource]](config.configurationCacheRefreshInterval),
        )
      ) ++
      ZLayer.service[ResourcesSyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Map[ResourceId, Resource]],
      source: ResourcesSyncClient,
  ) extends ResourceService:
    override def findByResourceId(resourceId: ResourceId): UIO[Option[Resource]] =
      cache.get.map(_.get(resourceId))

    override def refreshNow: Task[Unit] =
      source.getAll.flatMap(cache.set)
