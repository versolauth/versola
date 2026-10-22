package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.central.configuration.clients.est.{ClientEstRepository, EstCertificate, EstEnrollmentRow}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresClientEstRepository(xa: TransactorZIO) extends ClientEstRepository, BasicCodecs:
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec

  private case class Row(clientId: ClientId, thumbprint: String, serial: String, notAfter: Instant, issuedAt: Instant) derives DbCodec

  override def enable(clientId: ClientId, at: Instant): Task[Unit] =
    xa.connectMeasured("enable-client-est"):
      sql"""
        INSERT INTO client_est_enrollments (client_id, enrolled_at, chain_started_at)
        VALUES ($clientId, $at, NULL)
        ON CONFLICT (client_id) DO NOTHING
      """.update.run()
    .unit

  override def isEnabled(clientId: ClientId): Task[Boolean] =
    xa.connectMeasured("is-client-est-enabled"):
      sql"SELECT client_id FROM client_est_enrollments WHERE client_id = $clientId".query[ClientId].run().nonEmpty

  override def all: Task[Vector[EstEnrollmentRow]] =
    xa.connectMeasured("all-client-est-enrollments"):
      sql"""
        SELECT e.client_id, e.enrolled_at, e.chain_started_at, MAX(c.not_after)
        FROM client_est_enrollments e
        LEFT JOIN client_est_certificates c ON c.client_id = e.client_id
        GROUP BY e.client_id, e.enrolled_at, e.chain_started_at
      """.query[(ClientId, Instant, Option[Instant], Option[Instant])].run().map:
        case (clientId, enrolledAt, chainStartedAt, latestNotAfter) =>
          EstEnrollmentRow(clientId, enrolledAt, chainStartedAt, latestNotAfter)
      .toVector

  override def createToken(tokenHash: String, clientId: ClientId, expiresAt: Instant): Task[Unit] =
    xa.connectMeasured("create-client-est-token"):
      sql"""
        INSERT INTO client_est_tokens (token_hash, client_id, expires_at, used_at)
        VALUES ($tokenHash, $clientId, $expiresAt, NULL)
      """.update.run()
    .unit

  override def peekToken(tokenHash: String, now: Instant): Task[Option[ClientId]] =
    xa.connectMeasured("peek-client-est-token"):
      sql"""
        SELECT client_id FROM client_est_tokens
        WHERE token_hash = $tokenHash AND used_at IS NULL AND expires_at > $now
      """.query[ClientId].run().headOption

  override def consumeToken(tokenHash: String, now: Instant): Task[Option[ClientId]] =
    xa.connectMeasured("consume-client-est-token"):
      sql"""
        UPDATE client_est_tokens
        SET used_at = $now
        WHERE token_hash = $tokenHash AND used_at IS NULL AND expires_at > $now
        RETURNING client_id
      """.query[ClientId].run().headOption

  override def startChain(clientId: ClientId, at: Instant): Task[Unit] =
    xa.connectMeasured("start-client-est-chain"):
      sql"UPDATE client_est_enrollments SET chain_started_at = $at WHERE client_id = $clientId".update.run()
    .unit

  override def chainStartedAt(clientId: ClientId): Task[Option[Instant]] =
    xa.connectMeasured("client-est-chain-started-at"):
      sql"SELECT chain_started_at FROM client_est_enrollments WHERE client_id = $clientId".query[Option[Instant]].run().headOption.flatten

  override def recordCertificate(certificate: EstCertificate): Task[Unit] =
    xa.connectMeasured("record-client-est-certificate"):
      sql"""
        INSERT INTO client_est_certificates (thumbprint, client_id, serial, not_after, issued_at)
        VALUES (${certificate.thumbprint}, ${certificate.clientId}, ${certificate.serial}, ${certificate.notAfter}, ${certificate.issuedAt})
        ON CONFLICT (thumbprint) DO NOTHING
      """.update.run()
    .unit

  override def findCertificate(thumbprint: String): Task[Option[EstCertificate]] =
    xa.connectMeasured("find-client-est-certificate"):
      sql"""
        SELECT client_id, thumbprint, serial, not_after, issued_at FROM client_est_certificates WHERE thumbprint = $thumbprint
      """.query[Row].run().headOption.map: row =>
        EstCertificate(row.clientId, row.thumbprint, row.serial, row.notAfter, row.issuedAt)

object PostgresClientEstRepository:
  def live: ZLayer[TransactorZIO, Nothing, ClientEstRepository] =
    ZLayer.fromFunction(PostgresClientEstRepository(_))
