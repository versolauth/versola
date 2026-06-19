package versola.central.configuration.resources

import versola.central.configuration.edges.EdgeId
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{CreateResourceEndpointRequest, CreateResourceRequest, UpdateResourceRequest}
import versola.util.ReloadingCache
import versola.util.cel.CelEvaluator
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

trait ResourceService:
  def getTenantResources(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[ResourceRecord]]

  def getResourcesForSync(edgeId: Option[EdgeId]): Task[Vector[ResourceRecord]]

  def createResource(request: CreateResourceRequest): Task[Either[ResourceValidationError, ResourceId]]

  def updateResource(request: UpdateResourceRequest): Task[Either[ResourceValidationError, Unit]]

  def deleteResource(id: ResourceId): Task[Unit]

  def sync(event: SyncEvent.ResourcesUpdated): Task[Unit]

object ResourceService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[ResourceRepository & TenantRepository & CelEvaluator & Scope, Throwable, ResourceService] =
    ZLayer(ReloadingCache.make[Vector[ResourceRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      cache: ReloadingCache[Vector[ResourceRecord]],
      resourceRepository: ResourceRepository,
      tenantRepository: TenantRepository,
      celEvaluator: CelEvaluator,
  ) extends ResourceService:
    export resourceRepository.deleteResource

    override def getTenantResources(
        tenantId: TenantId,
        offset: Int,
        limit: Option[Int],
    ): Task[Vector[ResourceRecord]] =
      cache.get.map { records =>
        records
          .filter(_.tenantId == tenantId)
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def getResourcesForSync(edgeId: Option[EdgeId]): Task[Vector[ResourceRecord]] =
      edgeId match
        case None => cache.get
        case Some(id) =>
          for
            resources <- cache.get
            tenants <- tenantRepository.getAll
            allowedTenantIds = tenants.filter(_.edgeId.contains(id)).map(_.id).toSet
          yield resources.filter(r => allowedTenantIds.contains(r.tenantId))

    override def createResource(request: CreateResourceRequest): Task[Either[ResourceValidationError, ResourceId]] =
      validateEndpoints(request.endpoints).flatMap:
        case Some(error) => ZIO.left(error)
        case None =>
          resourceRepository.createResource(
            tenantId = request.tenantId,
            alias = request.alias,
            resource = request.resource,
            endpoints = request.endpoints.map(asRecord),
          ).map(Right(_))

    override def updateResource(request: UpdateResourceRequest): Task[Either[ResourceValidationError, Unit]] =
      validateEndpoints(request.createEndpoints).flatMap:
        case Some(error) => ZIO.left(error)
        case None =>
          resourceRepository.updateResource(
            id = request.id,
            aliasPatch = request.alias,
            resourcePatch = request.resource,
            deleteEndpoints = request.deleteEndpoints,
            addEndpoints = request.createEndpoints.map(asRecord),
          ).map(Right(_))

    override def sync(event: SyncEvent.ResourcesUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        resourceRepository.findResource(event.id),
      )

    private def validateEndpoints(
        endpoints: Vector[CreateResourceEndpointRequest],
    ): Task[Option[ResourceValidationError]] =
      ZIO.foldLeft(endpoints)(Option.empty[ResourceValidationError]):
        case (Some(err), _) => ZIO.succeed(Some(err))
        case (None, endpoint) => validateEndpoint(endpoint)

    private val validPathRegex = "^/([a-zA-Z0-9-]+(/[a-zA-Z0-9-]+)*)?$".r

    private def validateEndpoint(
        endpoint: CreateResourceEndpointRequest,
    ): Task[Option[ResourceValidationError]] =
      if !validPathRegex.matches(endpoint.path) then
        return ZIO.some(ResourceValidationError.InvalidEndpointPath(endpoint.id))
      val allowCheck = endpoint.allow.filter(_.trim.nonEmpty) match
        case None => ZIO.none
        case Some(expression) =>
          celEvaluator.validate(expression)
            .as(Option.empty[ResourceValidationError])
            .catchAll: err =>
              ZIO.some(ResourceValidationError.InvalidAllowExpression(endpoint.id, err.expression, err.message))

      allowCheck.flatMap:
        case Some(err) => ZIO.some(err)
        case None =>
          ZIO.foldLeft(endpoint.inject)(Option.empty[ResourceValidationError]):
            case (Some(err), _) => ZIO.succeed(Some(err))
            case (None, rule) =>
              celEvaluator.validate(rule.expression)
                .as(Option.empty[ResourceValidationError])
                .catchAll: err =>
                  ZIO.some(ResourceValidationError.InvalidInjectExpression(endpoint.id, rule.name, err.expression, err.message))

    private def asRecord(request: CreateResourceEndpointRequest) =
      ResourceEndpointRecord(
        id = request.id,
        path = request.path,
        method = request.method,
        fetchUserInfo = request.fetchUserInfo,
        allowExpression = request.allow,
        inject = request.inject,
      )
