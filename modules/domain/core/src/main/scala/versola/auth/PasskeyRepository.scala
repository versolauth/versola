package versola.auth

import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord, WebAuthnUserId}
import versola.user.model.UserId
import zio.{Task, ZIO, ZLayer}

import java.sql.JDBCType
import java.time.Instant
import java.util.UUID

trait PasskeyRepository:
  def insert(passkey: PasskeyRecord): Task[Unit]
  def findByCredentialId(credentialId: CredentialId): Task[Option[PasskeyRecord]]
  def findByUserId(userId: UserId): Task[Vector[PasskeyRecord]]
  def findByWebAuthnUserId(webauthnUserId: WebAuthnUserId): Task[Option[PasskeyRecord]]
  def updateSignatureCounter(credentialId: CredentialId, newCounter: Long, backedUp: Boolean): Task[Unit]
  def deleteByCredentialId(credentialId: CredentialId): Task[Unit]

