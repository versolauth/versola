package versola.central.configuration.metadata

import versola.central.CentralConfig
import versola.central.configuration.sync.SyncEvent
import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait ServerMetadataService:
  def getMetadata: UIO[Option[Json.Obj]]
  def upsertMetadata(metadata: Json.Obj): Task[Unit]
  def updateAuthorizationDetailType(`type`: String, op: SyncEvent.Op): Task[Unit]
  def sync(): Task[Unit]

object ServerMetadataService:
  private val authorizationDetailTypesKey = "authorization_details_types_supported"
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

    override def updateAuthorizationDetailType(`type`: String, op: SyncEvent.Op): Task[Unit] =
      for
        current <- repository.get
        metadata = current.fold(Json.Obj())(_.metadata)
        existingTypes = metadata.get(authorizationDetailTypesKey)
          .flatMap(_.as[Set[String]].toOption)
          .getOrElse(Set.empty)
        types = op match
          case SyncEvent.Op.DELETE => existingTypes - `type`
          case _ => existingTypes + `type`
        typesJson = Json.Arr(types.map(Json.Str(_)).toSeq*)
        fields = Json.Obj(
          (metadata.fields.filterNot(_._1 == authorizationDetailTypesKey) :+
            (authorizationDetailTypesKey -> typesJson))*
        )
        _ <- repository.upsert(fields)
      yield ()

    override def sync(): Task[Unit] =
      repository.get.flatMap(cache.set)
