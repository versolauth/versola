package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind, EdgeGroupRecord, EdgeGroupRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresEdgeGroupRepository(xa: TransactorZIO) extends EdgeGroupRepository, BasicCodecs:
  given DbCodec[EdgeGroupId] = DbCodec.StringCodec.biMap(EdgeGroupId(_), identity[String])
  given DbCodec[EdgeGroupKind] =
    DbCodec.StringCodec.biMap(code => EdgeGroupKind.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[EdgeGroupRecord] = DbCodec.derived

  override def getAll: Task[Vector[EdgeGroupRecord]] =
    xa.connectMeasured("get-all-edge-groups"):
      sql"SELECT id, kind FROM edge_groups ORDER BY id".query[EdgeGroupRecord].run()

  override def find(id: EdgeGroupId): Task[Option[EdgeGroupRecord]] =
    xa.connectMeasured("find-edge-group"):
      sql"SELECT id, kind FROM edge_groups WHERE id = $id".query[EdgeGroupRecord].run().headOption

  override def create(record: EdgeGroupRecord): Task[Boolean] =
    xa.connectMeasured("create-edge-group"):
      sql"""
        INSERT INTO edge_groups (id, kind) VALUES (${record.id}, ${record.kind})
        ON CONFLICT (id) DO NOTHING
      """.update.run() > 0

object PostgresEdgeGroupRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeGroupRepository] =
    ZLayer.fromFunction(PostgresEdgeGroupRepository(_))
