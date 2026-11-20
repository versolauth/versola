package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeCertificateRepository, EdgeId}
import versola.central.configuration.partners.PartnerStatus
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

  override def pastRenewalMidpoint(now: Instant, renewalShare: Double): Task[Long] =
    val midShare = renewalShare / 2
    val suspended = PartnerStatus.Suspended.code
    xa.connectMeasured("edge-certificates-past-renewal-midpoint"):
      // The status is read here, and not by the service, so that the count stays one query: a suspended partner's edge is meant to
      // lose its certificate, and counting it would page for what the operator did on purpose.
      sql"""
        SELECT count(*)
        FROM (
          SELECT DISTINCT ON (c.edge_id) c.not_after, c.issued_at
          FROM edge_certificates c
          JOIN edges e ON e.id = c.edge_id
          LEFT JOIN partners p ON p.group_id = e.group_id
          WHERE p.status IS NULL OR p.status <> $suspended
          ORDER BY c.edge_id, c.issued_at DESC, c.serial DESC
        ) latest
        WHERE not_after - $now::timestamptz < (not_after - issued_at) * $midShare::float8
      """.query[Long].run().head

object PostgresEdgeCertificateRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeCertificateRepository] =
    ZLayer.fromFunction(PostgresEdgeCertificateRepository(_))
