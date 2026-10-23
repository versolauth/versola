package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.central.configuration.clients.est.{ClientEstRepository, EstCertificate, EstEnrollmentRow, NewEstCertificate, RenewalRecorded}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer}

import java.time.Instant

class PostgresClientEstRepository(xa: TransactorZIO) extends ClientEstRepository, BasicCodecs:
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec

  private case class Row(
      clientId: ClientId,
      thumbprint: String,
      serial: String,
      notAfter: Instant,
      issuedAt: Instant,
      generation: Int,
      supersededAt: Option[Instant],
  ) derives DbCodec

  override def enable(clientId: ClientId, at: Instant): Task[Unit] =
    xa.connectMeasured("enable-client-est"):
      sql"""
        INSERT INTO client_est_enrollments (client_id, enrolled_at, generation, chain_started_at)
        VALUES ($clientId, $at, 0, NULL)
        ON CONFLICT (client_id) DO NOTHING
      """.update.run()
    .unit

  override def isEnabled(clientId: ClientId): Task[Boolean] =
    xa.connectMeasured("is-client-est-enabled"):
      sql"SELECT client_id FROM client_est_enrollments WHERE client_id = $clientId".query[ClientId].run().nonEmpty

  override def generation(clientId: ClientId): Task[Option[Int]] =
    xa.connectMeasured("client-est-generation"):
      sql"SELECT generation FROM client_est_enrollments WHERE client_id = $clientId".query[Int].run().headOption

  override def all: Task[Vector[EstEnrollmentRow]] =
    xa.connectMeasured("all-client-est-enrollments"):
      sql"""
        SELECT e.client_id, e.enrolled_at, e.chain_started_at, MAX(c.not_after)
        FROM client_est_enrollments e
        LEFT JOIN client_est_certificates c ON c.client_id = e.client_id AND c.generation = e.generation
        GROUP BY e.client_id, e.enrolled_at, e.chain_started_at
      """.query[(ClientId, Instant, Option[Instant], Option[Instant])].run().map:
        case (clientId, enrolledAt, chainStartedAt, latestNotAfter) =>
          EstEnrollmentRow(clientId, enrolledAt, chainStartedAt, latestNotAfter)
      .toVector

  override def createToken(tokenHash: String, clientId: ClientId, expiresAt: Instant): Task[Unit] =
    xa.transactMeasured("create-client-est-token"):
      sql"DELETE FROM client_est_tokens WHERE client_id = $clientId AND used_at IS NULL".update.run()
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

  override def releaseToken(tokenHash: String): Task[Unit] =
    xa.connectMeasured("release-client-est-token"):
      sql"UPDATE client_est_tokens SET used_at = NULL WHERE token_hash = $tokenHash".update.run()
    .unit

  override def revoke(clientId: ClientId): Task[Unit] =
    xa.transactMeasured("revoke-client-est"):
      sql"""
        UPDATE client_est_enrollments SET generation = generation + 1, chain_started_at = NULL WHERE client_id = $clientId
      """.update.run()
      sql"DELETE FROM client_est_tokens WHERE client_id = $clientId AND used_at IS NULL".update.run()
    .unit

  override def recordEnrollment(certificate: NewEstCertificate, now: Instant): Task[Unit] =
    xa.transactMeasured("record-client-est-enrollment"):
      val clientId = certificate.clientId
      val generation = sql"""
        UPDATE client_est_enrollments SET generation = generation + 1, chain_started_at = $now
        WHERE client_id = $clientId
        RETURNING generation
      """.query[Int].run().head
      insert(certificate, generation)
    .unit

  override def recordRenewal(
      presentedThumbprint: String,
      certificate: NewEstCertificate,
      generation: Int,
      now: Instant,
      maxAge: Duration,
      autoExtend: Boolean,
  ): Task[RenewalRecorded] =
    xa.transactMeasured("record-client-est-renewal"):
      val clientId = certificate.clientId
      // Locked, so that a revocation or an extension that comes while the CA is answering is seen, not overwritten.
      val locked = sql"""
        SELECT generation, chain_started_at FROM client_est_enrollments WHERE client_id = $clientId FOR UPDATE
      """.query[(Int, Option[Instant])].run().headOption
      locked match
        case Some((current, started)) if current == generation =>
          val begun = started.getOrElse(now)
          val ended = begun.plus(maxAge).isBefore(now)
          if ended && !autoExtend then RenewalRecorded.ChainEnded
          else
            val chainStart = if ended then now else begun
            if chainStart != begun || started.isEmpty then
              sql"UPDATE client_est_enrollments SET chain_started_at = $chainStart WHERE client_id = $clientId".update.run()
            sql"""
              UPDATE client_est_certificates SET superseded_at = $now
              WHERE thumbprint = $presentedThumbprint AND superseded_at IS NULL
            """.update.run()
            insert(certificate, generation)
            RenewalRecorded.Recorded(chainStart)
        case _ => RenewalRecorded.GenerationChanged

  override def extendChain(clientId: ClientId, at: Instant): Task[Unit] =
    xa.connectMeasured("extend-client-est-chain"):
      sql"UPDATE client_est_enrollments SET chain_started_at = $at WHERE client_id = $clientId".update.run()
    .unit

  override def chainStartedAt(clientId: ClientId): Task[Option[Instant]] =
    xa.connectMeasured("client-est-chain-started-at"):
      sql"SELECT chain_started_at FROM client_est_enrollments WHERE client_id = $clientId".query[Option[Instant]].run().headOption.flatten

  override def findCertificate(thumbprint: String): Task[Option[EstCertificate]] =
    xa.connectMeasured("find-client-est-certificate"):
      sql"""
        SELECT client_id, thumbprint, serial, not_after, issued_at, generation, superseded_at
        FROM client_est_certificates WHERE thumbprint = $thumbprint
      """.query[Row].run().headOption.map: row =>
        EstCertificate(row.clientId, row.thumbprint, row.serial, row.notAfter, row.issuedAt, row.generation, row.supersededAt)

  private def insert(certificate: NewEstCertificate, generation: Int)(using DbCon): Unit =
    sql"""
      INSERT INTO client_est_certificates (thumbprint, client_id, serial, not_after, issued_at, generation, superseded_at)
      VALUES (${certificate.thumbprint}, ${certificate.clientId}, ${certificate.serial}, ${certificate.notAfter}, ${certificate.issuedAt}, $generation, NULL)
      ON CONFLICT (thumbprint) DO NOTHING
    """.update.run()
    ()

object PostgresClientEstRepository:
  def live: ZLayer[TransactorZIO, Nothing, ClientEstRepository] =
    ZLayer.fromFunction(PostgresClientEstRepository(_))
