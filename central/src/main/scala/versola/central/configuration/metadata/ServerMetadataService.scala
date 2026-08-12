package versola.central.configuration.metadata

import versola.central.CentralConfig
import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait ServerMetadataService:
  def getMetadata: UIO[Option[Json.Obj]]
  def upsertMetadata(metadata: Json.Obj): Task[Unit]
  def sync(): Task[Unit]

object ServerMetadataService:
  def live: ZLayer[ServerMetadataRepository & Scope & CentralConfig, Throwable, ServerMetadataService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Option[ServerMetadataRecord]](Schedule.spaced(config.configurationCacheRefreshInterval)),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _))

  case class Impl(
      cache: ReloadingCache[Option[ServerMetadataRecord]],
      repository: ServerMetadataRepository,
  ) extends ServerMetadataService:
    override def getMetadata: UIO[Option[Json.Obj]] =
      cache.get.map(_.map(_.metadata))

    override def upsertMetadata(metadata: Json.Obj): Task[Unit] =
      repository.upsert(metadata)

    override def sync(): Task[Unit] =
      repository.get.flatMap(cache.set)
