package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.central.configuration.clients.certificates.EdgeCertificateEnrollmentRepository
import versola.central.configuration.edges.EdgeId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresEdgeCertificateEnrollmentRepository(
    xa: TransactorZIO,
) extends EdgeCertificateEnrollmentRepository, BasicCodecs:

  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec

  override def enroll(clientId: ClientId, at: Instant): Task[Unit] =
    xa.connectMeasured("enroll-edge-certificate"):
      sql"""
        INSERT INTO edge_certificate_enrollment (client_id, enrolled_at)
        VALUES ($clientId, $at)
        ON CONFLICT (client_id) DO NOTHING
      """.update.run()
    .unit

  override def isEnrolled(clientId: ClientId): Task[Boolean] =
    xa.connectMeasured("is-edge-certificate-enrolled"):
      sql"""SELECT client_id FROM edge_certificate_enrollment WHERE client_id = $clientId""".query[ClientId].run().nonEmpty

  override def enrolledClients: Task[Set[ClientId]] =
    xa.connectMeasured("enrolled-edge-certificate-clients"):
      sql"""SELECT client_id FROM edge_certificate_enrollment""".query[ClientId].run().toSet

  override def recordIssued(clientId: ClientId, serial: String, at: Instant, edgeId: EdgeId): Task[Unit] =
    xa.connectMeasured("record-edge-certificate-issued"):
      sql"""
        UPDATE edge_certificate_enrollment
        SET last_serial = $serial, last_issued_at = $at, last_edge_id = $edgeId
        WHERE client_id = $clientId
      """.update.run()
    .unit

  override def delete(clientId: ClientId): Task[Unit] =
    xa.connectMeasured("delete-edge-certificate-enrollment"):
      sql"""DELETE FROM edge_certificate_enrollment WHERE client_id = $clientId""".update.run()
    .unit

object PostgresEdgeCertificateEnrollmentRepository:
  def live: ZLayer[TransactorZIO, Throwable, EdgeCertificateEnrollmentRepository] =
    ZLayer.fromFunction(PostgresEdgeCertificateEnrollmentRepository(_))
