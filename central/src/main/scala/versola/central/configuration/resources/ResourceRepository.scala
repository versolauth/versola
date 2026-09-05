package versola.central.configuration.resources

import versola.central.configuration.ResourceUri
import versola.central.configuration.clients.ClientId
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
      audience: List[ClientId],
      endpoints: Vector[ResourceEndpointRecord],
      secret: Option[Array[Byte]],
  ): Task[Unit]

  def updateResource(
      resourceId: ResourceId,
      resourcePatch: Option[ResourceUri],
      audiencePatch: Option[List[ClientId]],
      addEndpoints: Vector[ResourceEndpointRecord],
      deleteEndpoints: Set[ResourceEndpointId],
  ): Task[Unit]

  /** Returns true only when an internal resource with no pending rotation was rotated. */
  def rotateSecret(resourceId: ResourceId, newSecret: Array[Byte]): Task[Boolean]

  /** Converts a public resource to internal exactly once without creating a rotation grace period. */
  def initializeSecret(resourceId: ResourceId, secret: Array[Byte]): Task[Boolean]

  def deletePreviousSecret(resourceId: ResourceId): Task[Unit]

  def deleteResource(
      resourceId: ResourceId,
  ): Task[Unit]