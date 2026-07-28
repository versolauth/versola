package versola.central.configuration.metadata

import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZLayer}

trait ServerMetadataService:
  def getMetadata: UIO[Option[Json.Obj]]
  def upsertMetadata(metadata: Json.Obj): Task[Unit]
  def sync(): Task[Unit]

object ServerMetadataService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[ServerMetadataRepository & Scope, Throwable, ServerMetadataService] =
    ZLayer(ReloadingCache.make[Option[ServerMetadataRecord]](schedule))
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
