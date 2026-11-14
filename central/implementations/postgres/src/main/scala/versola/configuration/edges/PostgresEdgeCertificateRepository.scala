package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeCertificateRepository, EdgeId}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresEdgeCertificateRepository(xa: TransactorZIO) extends EdgeCertificateRepository, BasicCodecs:
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec

  override def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit] =
    xa.connectMeasured("record-edge-certificate"):
      sql"""
        INSERT INTO edge_certificates (edge_id, serial, not_after, issued_at)
        VALUES ($edgeId, $serial, $notAfter, $issuedAt)
        ON CONFLICT (edge_id, serial) DO NOTHING
      """.update.run()
    .unit

  override def latest: Task[Map[EdgeId, EdgeCertificateRepository.Issued]] =
    xa.connectMeasured("latest-edge-certificates"):
      sql"""
        SELECT DISTINCT ON (edge_id) edge_id, serial, not_after, issued_at
        FROM edge_certificates
        ORDER BY edge_id, issued_at DESC, serial DESC
      """.query[(EdgeId, String, Instant, Instant)].run()
        .map((edgeId, serial, notAfter, issuedAt) => edgeId -> EdgeCertificateRepository.Issued(serial, notAfter, issuedAt))
        .toMap

object PostgresEdgeCertificateRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeCertificateRepository] =
    ZLayer.fromFunction(PostgresEdgeCertificateRepository(_))
