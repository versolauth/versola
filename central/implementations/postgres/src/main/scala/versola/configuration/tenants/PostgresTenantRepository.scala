package versola.configuration.tenants

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeGroupId, EdgeId}
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresTenantRepository(xa: TransactorZIO) extends TenantRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[EdgeGroupId] = DbCodec.StringCodec.biMap(EdgeGroupId(_), identity[String])
  given DbCodec[TenantRecord] = DbCodec.derived

  override def getAll: Task[Vector[TenantRecord]] =
    xa.connectMeasured("get-all-tenants"):
      sql"""SELECT id, description, edge_group_id FROM tenants ORDER BY id"""
        .query[TenantRecord]
        .run()

  override def getServedBy(edgeId: EdgeId): Task[Vector[TenantId]] =
    xa.connectMeasured("get-tenants-served-by-edge"):
      sql"""
        SELECT tenants.id FROM tenants
        JOIN edges ON edges.group_id = tenants.edge_group_id
        WHERE edges.id = $edgeId
        ORDER BY tenants.id
      """.query[TenantId].run()

  override def createTenant(
      id: TenantId,
      description: String,
      edgeGroupId: Option[EdgeGroupId],
  ): Task[Unit] =
    xa.connectMeasured("create-tenant"):
      sql"""
        INSERT INTO tenants (id, description, edge_group_id) VALUES ($id, $description, $edgeGroupId)
        ON CONFLICT (id) DO UPDATE SET description = EXCLUDED.description, edge_group_id = EXCLUDED.edge_group_id
      """.update.run()
    .unit

  override def updateTenant(
      id: TenantId,
      description: String,
      edgeGroupId: Option[EdgeGroupId],
  ): Task[Unit] =
    xa.connectMeasured("update-tenant"):
      sql"""UPDATE tenants SET description = $description, edge_group_id = $edgeGroupId WHERE id = $id""".update.run()
    .unit

  override def deleteTenant(id: TenantId): Task[Unit] =
    xa.connectMeasured("delete-tenant"):
      sql"""DELETE FROM tenants WHERE id = $id""".update.run()
    .unit

object PostgresTenantRepository:
  def live: ZLayer[TransactorZIO, Nothing, TenantRepository] =
    ZLayer.fromFunction(PostgresTenantRepository(_))
