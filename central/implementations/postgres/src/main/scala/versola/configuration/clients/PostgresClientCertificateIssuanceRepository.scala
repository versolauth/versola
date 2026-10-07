package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.util.postgres.BasicCodecs
import versola.central.configuration.clients.certificates.{ClientCertificateIssuance, ClientCertificateIssuanceRepository}
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresClientCertificateIssuanceRepository(
    xa: TransactorZIO,
) extends ClientCertificateIssuanceRepository, BasicCodecs:

  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[ClientCertificateIssuance] = DbCodec.derived

  override def upsert(issuance: ClientCertificateIssuance): Task[Unit] =
    xa.connectMeasured("upsert-client-certificate-issuance"):
      sql"""
        INSERT INTO client_certificate_issuance (client_id, serial, not_after, issued_at)
        VALUES (${issuance.clientId}, ${issuance.serial}, ${issuance.notAfter}, ${issuance.issuedAt})
        ON CONFLICT (client_id) DO UPDATE
        SET serial = EXCLUDED.serial, not_after = EXCLUDED.not_after, issued_at = EXCLUDED.issued_at
      """.update.run()
    .unit

  override def find(clientId: ClientId): Task[Option[ClientCertificateIssuance]] =
    xa.connectMeasured("find-client-certificate-issuance"):
      sql"""
        SELECT client_id, serial, not_after, issued_at
        FROM client_certificate_issuance
        WHERE client_id = $clientId
      """.query[ClientCertificateIssuance].run().headOption

  override def delete(clientId: ClientId): Task[Unit] =
    xa.connectMeasured("delete-client-certificate-issuance"):
      sql"""DELETE FROM client_certificate_issuance WHERE client_id = $clientId""".update.run()
    .unit

  override def expiringBefore(deadline: Instant): Task[Vector[ClientCertificateIssuance]] =
    xa.connectMeasured("expiring-client-certificates"):
      sql"""
        SELECT client_id, serial, not_after, issued_at
        FROM client_certificate_issuance
        WHERE not_after < $deadline
        ORDER BY not_after
      """.query[ClientCertificateIssuance].run()

object PostgresClientCertificateIssuanceRepository:
  def live: ZLayer[TransactorZIO, Throwable, ClientCertificateIssuanceRepository] =
    ZLayer.fromFunction(PostgresClientCertificateIssuanceRepository(_))
