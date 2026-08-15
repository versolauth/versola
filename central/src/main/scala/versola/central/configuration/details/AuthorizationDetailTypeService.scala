package versola.central.configuration.details

import versola.central.CentralConfig
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateAuthorizationDetailTypeRequest, UpdateAuthorizationDetailTypeRequest}
import versola.central.configuration.metadata.ServerMetadataService
import versola.util.{JsonSchemaValidator, ReloadingCache}
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.{Schema, derived}
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

enum AuthorizationDetailTypeValidationError derives JsonCodec, Schema:
  case InvalidSchema(errors: List[String])

trait AuthorizationDetailTypeService:
  def getAllTypes: Task[Vector[AuthorizationDetailTypeRecord]]

  def getTenantTypes(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[AuthorizationDetailTypeRecord]]

  def createType(
      request: CreateAuthorizationDetailTypeRequest,
  ): Task[Either[AuthorizationDetailTypeValidationError, Unit]]

  def updateType(
      request: UpdateAuthorizationDetailTypeRequest,
  ): Task[Either[AuthorizationDetailTypeValidationError, Unit]]

  def deleteType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
  ): Task[Unit]

  def sync(
      event: SyncEvent.AuthorizationDetailTypesUpdated,
  ): Task[Unit]

object AuthorizationDetailTypeService:
  def live: ZLayer[
    AuthorizationDetailTypeRepository & JsonSchemaValidator & ServerMetadataService & Scope & CentralConfig,
    Throwable,
    AuthorizationDetailTypeService,
  ] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[AuthorizationDetailTypeRecord]](
          Schedule.spaced(config.configurationCacheRefreshInterval),
        ),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      cache: ReloadingCache[Vector[AuthorizationDetailTypeRecord]],
      repository: AuthorizationDetailTypeRepository,
      schemaValidator: JsonSchemaValidator,
      serverMetadataService: ServerMetadataService,
  ) extends AuthorizationDetailTypeService:

    override def getAllTypes: Task[Vector[AuthorizationDetailTypeRecord]] =
      cache.get

    override def getTenantTypes(
        tenantId: TenantId,
        offset: Int,
        limit: Option[Int],
    ): Task[Vector[AuthorizationDetailTypeRecord]] =
      cache.get.map { records =>
        records.filter(_.tenantId == tenantId)
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def createType(
        request: CreateAuthorizationDetailTypeRequest,
    ): Task[Either[AuthorizationDetailTypeValidationError, Unit]] =
      validated(request.schema):
        for
          _ <- repository.createType(request.tenantId, request.`type`, request.description, request.schema)
          _ <- updateMetadata(request.`type`, SyncEvent.Op.INSERT)
        yield ()

    override def updateType(
        request: UpdateAuthorizationDetailTypeRequest,
    ): Task[Either[AuthorizationDetailTypeValidationError, Unit]] =
      validated(request.schema):
        repository.updateType(request.tenantId, request.`type`, request.description, request.schema)

    override def deleteType(
        tenantId: TenantId,
        `type`: AuthorizationDetailType,
    ): Task[Unit] =
      for
        _ <- repository.deleteType(tenantId, `type`)
        _ <- updateMetadata(`type`, SyncEvent.Op.DELETE)
      yield ()

    override def sync(
        event: SyncEvent.AuthorizationDetailTypesUpdated,
    ): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.findType(event.tenantId, event.id),
      )

    /** Rejects a schema that is not itself a valid JSON Schema here, at write time, rather
      * than letting every authorize request carrying that type fail later in auth. */
    private def validated(schema: Json.Obj)(
        write: => Task[Unit],
    ): Task[Either[AuthorizationDetailTypeValidationError, Unit]] =
      schemaValidator.validateSchema(schema).flatMap:
        case Nil => write.as(Right(()))
        case errors => ZIO.left(AuthorizationDetailTypeValidationError.InvalidSchema(errors))

    private def updateMetadata(`type`: AuthorizationDetailType, op: SyncEvent.Op): Task[Unit] =
      serverMetadataService.updateAuthorizationDetailType(`type`, op)
