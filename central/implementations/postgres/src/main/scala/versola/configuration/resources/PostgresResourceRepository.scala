package versola.configuration.resources

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.central.configuration.ResourceUri
import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.resources.{
  ResourceEndpointId,
  ResourceEndpointRecord,
  ResourceId,
  ResourceRecord,
  ResourceRepository,
}
import versola.central.configuration.tenants.TenantId
import versola.util.Secret
import versola.util.postgres.BasicCodecs
import zio.json.JsonCodec
import zio.{Task, ZLayer}

class PostgresResourceRepository(xa: TransactorZIO) extends ResourceRepository, BasicCodecs:
  given DbCodec[ResourceId] = DbCodec.StringCodec.biMap(ResourceId(_), identity[String])
  given DbCodec[ResourceUri] = DbCodec.StringCodec.biMap(ResourceUri(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[List[ClientId]] =
    PgCodec.SeqCodec[String].biMap(_.map(ClientId(_)).toList, _.map(identity[String]))

  given JsonBDbCodec[ResourceEndpointRecord] =
    given JsonCodec[ResourceEndpointRecord] = JsonCodec.derived
    jsonBCodec

  given DbCodec[ResourceRecord] = DbCodec.derived

  private def findResourceQuery(resourceId: ResourceId) =
    sql"""
      SELECT tenant_id, resource_id, resource, audience, endpoints, secret, previous_secret FROM resources
      WHERE resource_id = $resourceId
    """.query[ResourceRecord]

  private val resourceColumns =
    "tenant_id, resource_id, resource, audience, endpoints, secret, previous_secret"

  override def getAll: Task[Vector[ResourceRecord]] =
    xa.connectMeasured("get-all-resources"):
      sql"""
        SELECT tenant_id, resource_id, resource, audience, endpoints, secret, previous_secret FROM resources
      """.query[ResourceRecord].run()

  override def getForEdge(edgeId: EdgeId): Task[Vector[ResourceRecord]] =
    xa.connectMeasured("get-edge-resources"):
      sql"""
        SELECT r.tenant_id, r.resource_id, r.resource, r.audience, r.endpoints, r.secret, r.previous_secret
        FROM resources r
        INNER JOIN edge_resources er ON er.resource_id = r.resource_id
        WHERE er.edge_id = $edgeId
        ORDER BY r.resource_id
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
      audience: List[ClientId],
      endpoints: Vector[ResourceEndpointRecord],
      secret: Option[Array[Byte]],
  ): Task[Unit] =
    xa.connectMeasured("create-resource"):
      sql"""
        INSERT INTO resources (resource_id, tenant_id, resource, audience, endpoints, secret)
        VALUES ($resourceId, $tenantId, $resource, $audience, $endpoints, ${secret.map(Secret(_))})
      """.update.run()
    .unit

  override def updateResource(
      resourceId: ResourceId,
      resourcePatch: Option[ResourceUri],
      audiencePatch: Option[List[ClientId]],
      addEndpoints: Vector[ResourceEndpointRecord],
      deleteEndpoints: Set[ResourceEndpointId],
  ): Task[Unit] =
    xa.transactMeasured("update-resource"):
      // Lock the row (READ_COMMITTED + FOR UPDATE) to prevent lost updates from concurrent writers.
      sql"""
        SELECT tenant_id, resource_id, resource, audience, endpoints, secret, previous_secret FROM resources
        WHERE resource_id = $resourceId
        FOR UPDATE
      """.query[ResourceRecord].run().headOption match
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
              audience = ${audiencePatch.getOrElse(resource.audience)},
              endpoints = $newEndpoints::jsonb[]
            WHERE resource_id = $resourceId
          """.update.run()
    .unit

  override def rotateSecret(resourceId: ResourceId, newSecret: Array[Byte]): Task[Boolean] =
    xa.connectMeasured("rotate-resource-secret"):
      sql"""
        UPDATE resources
        SET previous_secret = secret,
            secret = ${Secret(newSecret)}
        WHERE resource_id = $resourceId
          AND secret IS NOT NULL
          AND previous_secret IS NULL
      """.update.run()
    .map(_ > 0)

  override def deletePreviousSecret(resourceId: ResourceId): Task[Unit] =
    xa.connectMeasured("delete-previous-resource-secret"):
      sql"""
        UPDATE resources
        SET previous_secret = NULL
        WHERE resource_id = $resourceId
      """.update.run()
    .unit

  override def deleteResource(
      resourceId: ResourceId,
  ): Task[Unit] =
    xa.connectMeasured("delete-resource"):
      sql"""DELETE FROM resources WHERE resource_id = $resourceId""".update.run()
    .unit

object PostgresResourceRepository:
  def live: ZLayer[TransactorZIO, Nothing, ResourceRepository] =
    ZLayer.fromFunction(PostgresResourceRepository(_))
