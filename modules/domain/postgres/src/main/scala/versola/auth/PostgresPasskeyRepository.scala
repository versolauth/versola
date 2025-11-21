package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec.given
import com.augustnagro.magnum.pg.SqlArrayCodec
import com.augustnagro.magnum.pg.enums.PgStringToScalaEnumSqlArrayCodec
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord, WebAuthnUserId}
import versola.user.model.UserId
import zio.{Task, ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresPasskeyRepository(xa: TransactorZIO) extends PasskeyRepository:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[CredentialId] = DbCodec.ByteArrayCodec.biMap(CredentialId(_), identity)
  given DbCodec[WebAuthnUserId] = DbCodec.ByteArrayCodec.biMap(WebAuthnUserId(_), identity)

  given DbCodec[CredentialDeviceType] =
    DbCodec.StringCodec.biMap(CredentialDeviceType.valueOf, _.toString)

  given DbCodec[AuthenticatorTransport] =
    DbCodec.StringCodec.biMap(AuthenticatorTransport.valueOf, _.toString)

  given DbCodec[PasskeyRecord] = DbCodec.derived[PasskeyRecord]

  override def insert(passkey: PasskeyRecord): Task[Unit] =
    xa.connect:
      sql"""insert into passkeys (
              id, user_id, webauthn_user_id, public_key, signature_counter,
              device_type, backed_up, backup_eligible, transports,
              attestation_object, client_data_json, created_at, updated_at
            ) values (
              ${passkey.id}, ${passkey.userId}, ${passkey.webauthnUserId},
              ${passkey.publicKey}, ${passkey.signatureCounter},
              ${passkey.deviceType}, ${passkey.backedUp}, ${passkey.backupEligible},
              ${passkey.transports}, ${passkey.attestationObject}, ${passkey.clientDataJson},
              ${passkey.createdAt}, ${passkey.updatedAt}
            )""".update.run()

  override def findByCredentialId(credentialId: CredentialId): Task[Option[PasskeyRecord]] =
    xa.connect:
      sql"""select id, user_id, webauthn_user_id, public_key, signature_counter,
                   device_type, backed_up, backup_eligible, transports,
                   attestation_object, client_data_json, created_at, updated_at
            from passkeys
            where id = $credentialId"""
        .query[PasskeyRecord]
        .run()
        .headOption

  override def findByUserId(userId: UserId): Task[Vector[PasskeyRecord]] =
    xa.connect:
      sql"""select id, user_id, webauthn_user_id, public_key, signature_counter,
                   device_type, backed_up, backup_eligible, transports,
                   attestation_object, client_data_json, created_at, updated_at
            from passkeys
            where user_id = $userId
            order by created_at desc"""
        .query[PasskeyRecord]
        .run()

  override def findByWebAuthnUserId(webauthnUserId: WebAuthnUserId): Task[Option[PasskeyRecord]] =
    xa.connect:
      sql"""select id, user_id, webauthn_user_id, public_key, signature_counter,
                   device_type, backed_up, backup_eligible, transports,
                   attestation_object, client_data_json, created_at, updated_at
            from passkeys
            where webauthn_user_id = $webauthnUserId
            limit 1"""
        .query[PasskeyRecord]
        .run()
        .headOption

  override def updateSignatureCounter(credentialId: CredentialId, newCounter: Long, backedUp: Boolean): Task[Unit] =
    xa.connect:
      sql"""update passkeys
            set signature_counter = $newCounter,
                backed_up = $backedUp,
                updated_at = ${Instant.now()}
            where id = $credentialId"""
        .update
        .run()

  override def deleteByCredentialId(credentialId: CredentialId): Task[Unit] =
    xa.connect:
      sql"""delete from passkeys where id = $credentialId"""
        .update
        .run()
