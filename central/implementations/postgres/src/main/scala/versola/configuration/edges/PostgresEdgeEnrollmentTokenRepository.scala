package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeEnrollmentTokenRepository, EdgeId}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresEdgeEnrollmentTokenRepository(xa: TransactorZIO) extends EdgeEnrollmentTokenRepository, BasicCodecs:
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])

  override def create(tokenHash: String, edgeId: EdgeId, expiresAt: Instant): Task[Unit] =
    xa.connectMeasured("create-edge-enrollment-token"):
      sql"""
        INSERT INTO edge_enrollment_tokens (token_hash, edge_id, expires_at, used_at)
        VALUES ($tokenHash, $edgeId, $expiresAt, NULL)
      """.update.run()
    .unit

  override def peek(tokenHash: String, now: Instant): Task[Option[EdgeId]] =
    xa.connectMeasured("peek-edge-enrollment-token"):
      sql"""
        SELECT edge_id FROM edge_enrollment_tokens
        WHERE token_hash = $tokenHash AND used_at IS NULL AND expires_at > $now
      """.query[EdgeId].run().headOption

  override def consume(tokenHash: String, now: Instant): Task[Option[EdgeId]] =
    xa.connectMeasured("consume-edge-enrollment-token"):
      sql"""
        UPDATE edge_enrollment_tokens
        SET used_at = $now
        WHERE token_hash = $tokenHash AND used_at IS NULL AND expires_at > $now
        RETURNING edge_id
      """.query[EdgeId].run().headOption

object PostgresEdgeEnrollmentTokenRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeEnrollmentTokenRepository] =
    ZLayer.fromFunction(PostgresEdgeEnrollmentTokenRepository(_))
