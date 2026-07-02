package versola.central.configuration.resources

import versola.central.configuration.ResourceUri
import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task

trait ResourceRepository extends CacheSource[Vector[ResourceRecord]]:

  def getAll: Task[Vector[ResourceRecord]]

  def findResource(
      resourceId: ResourceId,
  ): Task[Option[ResourceRecord]]

  def createResource(
      tenantId: TenantId,
      resourceId: ResourceId,
      resource: ResourceUri,
      endpoints: Vector[ResourceEndpointRecord],
  ): Task[Unit]

  def updateResource(
      resourceId: ResourceId,
      resourcePatch: Option[ResourceUri],
      addEndpoints: Vector[ResourceEndpointRecord],
      deleteEndpoints: Set[ResourceEndpointId],
  ): Task[Unit]

  def deleteResource(
      resourceId: ResourceId,
  ): Task[Unit]