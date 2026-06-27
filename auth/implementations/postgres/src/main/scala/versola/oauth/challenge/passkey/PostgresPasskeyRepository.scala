package versola.oauth.challenge.passkey

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresPasskeyRepository(xa: TransactorZIO) extends PasskeyRepository, BasicCodecs:

  given DbCodec[CredentialId] = DbCodec.ByteArrayCodec.biMap(CredentialId(_), identity[Array[Byte]])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[CredentialDeviceType] = DbCodec.StringCodec.biMap(CredentialDeviceType.valueOf, _.toString)
  given DbCodec[AuthenticatorTransport] = DbCodec.StringCodec.biMap(AuthenticatorTransport.valueOf, _.toString)

  given SqlArrayCodec[AuthenticatorTransport] = new SqlArrayCodec[AuthenticatorTransport]:
    val jdbcTypeName: String = "text"
    def readArray(array: Object): Array[AuthenticatorTransport] =
      array.asInstanceOf[Array[String]].map(AuthenticatorTransport.valueOf)
    def toArrayObj(entity: AuthenticatorTransport): Object = entity.toString

  given DbCodec[PasskeyRecord] = DbCodec.derived[PasskeyRecord]

  override def insert(record: PasskeyRecord): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO passkeys (
          id, user_id, public_key, signature_counter, device_type,
          backed_up, backup_eligible, transports, attestation_object, client_data_json,
          aaguid, name, last_used_at, created_at, updated_at
        ) VALUES (
          ${record.id}, ${record.userId}, ${record.publicKey},
          ${record.signatureCounter}, ${record.deviceType}, ${record.backedUp}, ${record.backupEligible},
          ${record.transports}, ${record.attestationObject}, ${record.clientDataJson},
          ${record.aaguid}, ${record.name}, ${record.lastUsedAt}, ${record.createdAt}, ${record.updatedAt}
        )
      """.update.run()
    .unit

  override def findByCredentialIdAndUser(id: CredentialId, userId: UserId): Task[Option[PasskeyRecord]] =
    xa.connect:
      sql"""SELECT id, user_id, public_key, signature_counter, device_type,
              backed_up, backup_eligible, transports, attestation_object, client_data_json,
              aaguid, name, last_used_at, created_at, updated_at
            FROM passkeys WHERE id = $id AND user_id = $userId"""
        .query[PasskeyRecord]
        .run()
        .headOption

  override def findByCredentialId(id: CredentialId): Task[Vector[PasskeyRecord]] =
    xa.connect:
      sql"""SELECT id, user_id, public_key, signature_counter, device_type,
              backed_up, backup_eligible, transports, attestation_object, client_data_json,
              aaguid, name, last_used_at, created_at, updated_at
            FROM passkeys WHERE id = $id"""
        .query[PasskeyRecord]
        .run()

  override def listByUser(userId: UserId): Task[Vector[PasskeyRecord]] =
    xa.connect:
      sql"""SELECT id, user_id, public_key, signature_counter, device_type,
              backed_up, backup_eligible, transports, attestation_object, client_data_json,
              aaguid, name, last_used_at, created_at, updated_at
            FROM passkeys WHERE user_id = $userId ORDER BY created_at"""
        .query[PasskeyRecord]
        .run()

  override def updateUsage(id: CredentialId, signatureCounter: Long, lastUsedAt: Instant): Task[Boolean] =
    xa.connect:
      sql"""
      UPDATE passkeys
      SET signature_counter = $signatureCounter, last_used_at = $lastUsedAt, updated_at = $lastUsedAt
      WHERE id = $id AND signature_counter < $signatureCounter
    """.update.run() > 0

  override def rename(id: CredentialId, userId: UserId, name: Option[String]): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE passkeys
        SET name = $name
        WHERE id = $id AND user_id = $userId
      """.update.run()
    .unit

  override def deleteByUser(id: CredentialId, userId: UserId): Task[Unit] =
    xa.connect:
      sql"DELETE FROM passkeys WHERE id = $id AND user_id = $userId".update.run()
    .unit

  override def delete(id: CredentialId): Task[Unit] =
    xa.connect:
      sql"DELETE FROM passkeys WHERE id = $id".update.run()
    .unit

object PostgresPasskeyRepository:
  def live: ZLayer[TransactorZIO, Throwable, PasskeyRepository] =
    ZLayer.fromFunction(PostgresPasskeyRepository(_))
