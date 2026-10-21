package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeId, EdgeIdentityChainRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresEdgeIdentityChainRepository(xa: TransactorZIO) extends EdgeIdentityChainRepository, BasicCodecs:
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])

  override def start(edgeId: EdgeId, at: Instant): Task[Unit] =
    xa.connectMeasured("start-edge-identity-chain"):
      sql"""
        INSERT INTO edge_identity_chains (edge_id, started_at) VALUES ($edgeId, $at)
        ON CONFLICT (edge_id) DO UPDATE SET started_at = EXCLUDED.started_at
      """.update.run()
    .unit

  override def startedAt(edgeId: EdgeId): Task[Option[Instant]] =
    xa.connectMeasured("edge-identity-chain-started-at"):
      sql"SELECT started_at FROM edge_identity_chains WHERE edge_id = $edgeId".query[Instant].run().headOption

  override def all: Task[Map[EdgeId, Instant]] =
    xa.connectMeasured("all-edge-identity-chains"):
      sql"SELECT edge_id, started_at FROM edge_identity_chains".query[(EdgeId, Instant)].run().toMap

object PostgresEdgeIdentityChainRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeIdentityChainRepository] =
    ZLayer.fromFunction(PostgresEdgeIdentityChainRepository(_))
