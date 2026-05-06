package versola.configuration.tenants

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresTenantRepository(xa: TransactorZIO) extends TenantRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[TenantRecord] = DbCodec.derived

  override def getAll: Task[Vector[TenantRecord]] =
    xa.connect:
      sql"""SELECT id, description, edge_id FROM tenants ORDER BY id"""
        .query[TenantRecord]
        .run()

  override def createTenant(
      id: TenantId,
      description: String,
      edgeId: Option[EdgeId],
  ): Task[Unit] =
    xa.connect:
      sql"""INSERT INTO tenants (id, description, edge_id) VALUES ($id, $description, $edgeId)""".update.run()
    .unit

  override def updateTenant(
      id: TenantId,
      description: String,
      edgeId: Option[EdgeId],
  ): Task[Unit] =
    xa.connect:
      sql"""UPDATE tenants SET description = $description, edge_id = $edgeId WHERE id = $id""".update.run()
    .unit

  override def deleteTenant(id: TenantId): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM tenants WHERE id = $id""".update.run()
    .unit

object PostgresTenantRepository:
  def live: ZLayer[TransactorZIO, Nothing, TenantRepository] =
    ZLayer.fromFunction(PostgresTenantRepository(_))
