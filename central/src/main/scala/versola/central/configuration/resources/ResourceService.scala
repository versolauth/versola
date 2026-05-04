package versola.central.configuration.resources

import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateResourceEndpointRequest, CreateResourceRequest, UpdateResourceRequest}
import versola.util.ReloadingCache
import zio.{Cause, Ref, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

trait ResourceService:
  def getTenantResources(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[ResourceRecord]]

  def createResource(request: CreateResourceRequest): Task[ResourceId]

  def updateResource(request: UpdateResourceRequest): Task[Unit]

  def deleteResource(id: ResourceId): Task[Unit]

  def sync(event: SyncEvent.ResourcesUpdated): Task[Unit]

object ResourceService:
  def live(
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZLayer[ResourceRepository & Scope, Throwable, ResourceService] =
    ZLayer(ReloadingCache.make[Vector[ResourceRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[ResourceRecord]],
      resourceRepository: ResourceRepository,
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

    override def createResource(request: CreateResourceRequest): Task[ResourceId] =
      resourceRepository.createResource(
        tenantId = request.tenantId,
        resource = request.resource,
        endpoints = request.endpoints.map(asRecord),
      )

    override def updateResource(request: UpdateResourceRequest): Task[Unit] =
      resourceRepository.updateResource(
        id = request.id,
        resourcePatch = request.resource,
        deleteEndpoints = request.deleteEndpoints,
        addEndpoints = request.createEndpoints.map(asRecord),
      )

    override def sync(event: SyncEvent.ResourcesUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        resourceRepository.findResource(event.id),
      )

    private def asRecord(request: CreateResourceEndpointRequest) =
      ResourceEndpointRecord(
        id = request.id,
        path = request.path,
        method = request.method,
        fetchUserInfo = request.fetchUserInfo,
        allowRules = request.allowRules,
        denyRules = request.denyRules,
        injectHeaders = request.injectHeaders,
      )
