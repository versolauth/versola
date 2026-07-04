package versola.configuration.resources

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.central.configuration.ResourceUri
import versola.central.configuration.resources.{
  ResourceEndpointId,
  ResourceEndpointRecord,
  ResourceId,
  ResourceRecord,
  ResourceRepository,
}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.json.JsonCodec
import zio.{Task, ZLayer}

class PostgresResourceRepository(xa: TransactorZIO) extends ResourceRepository, BasicCodecs:
  given DbCodec[ResourceId] = DbCodec.StringCodec.biMap(ResourceId(_), identity[String])
  given DbCodec[ResourceUri] = DbCodec.StringCodec.biMap(ResourceUri(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])

  given JsonBDbCodec[ResourceEndpointRecord] =
    given JsonCodec[ResourceEndpointRecord] = JsonCodec.derived
    jsonBCodec

  given DbCodec[ResourceRecord] = DbCodec.derived

  private def findResourceQuery(resourceId: ResourceId) =
    sql"""
      SELECT tenant_id, alias AS resource_id, resource, endpoints FROM resources
      WHERE alias = $resourceId
    """.query[ResourceRecord]

  override def getAll: Task[Vector[ResourceRecord]] =
    xa.connectMeasured("get-all-resources"):
      sql"""
        SELECT tenant_id, alias AS resource_id, resource, endpoints FROM resources
      """.query[ResourceRecord].run()

  override def findResource(
      resourceId: ResourceId,
  ): Task[Option[ResourceRecord]] =
    xa.connectMeasured("find-resource"):
      findResourceQuery(resourceId).run().headOption

  override def createResource(
      tenantId: TenantId,
      resourceId: ResourceId,
      resource: ResourceUri,
      endpoints: Vector[ResourceEndpointRecord],
  ): Task[Unit] =
    xa.connectMeasured("create-resource"):
      sql"""
        INSERT INTO resources (tenant_id, alias, resource, endpoints)
        VALUES ($tenantId, $resourceId, $resource, $endpoints)
      """.update.run()
    .unit

  override def updateResource(
      resourceId: ResourceId,
      resourcePatch: Option[ResourceUri],
      addEndpoints: Vector[ResourceEndpointRecord],
      deleteEndpoints: Set[ResourceEndpointId],
  ): Task[Unit] =
    xa.repeatableRead.transactMeasured("update-resource"):
      findResourceQuery(resourceId).run().headOption match
        case None => ()
        case Some(resource) =>
          val endpointsToRemove = deleteEndpoints ++ addEndpoints.map(_.id)
          val newEndpoints = resource.endpoints
            .filterNot(endpoint => endpointsToRemove.contains(endpoint.id))
            .appendedAll(addEndpoints)

          sql"""
            UPDATE resources
            SET
              resource = ${resourcePatch.getOrElse(resource.resource)},
              endpoints = $newEndpoints::jsonb[]
            WHERE alias = $resourceId
          """.update.run()
    .unit

  override def deleteResource(
      resourceId: ResourceId,
  ): Task[Unit] =
    xa.connectMeasured("delete-resource"):
      sql"""DELETE FROM resources WHERE alias = $resourceId""".update.run()
    .unit

object PostgresResourceRepository:
  def live: ZLayer[TransactorZIO, Nothing, ResourceRepository] =
    ZLayer.fromFunction(PostgresResourceRepository(_))
