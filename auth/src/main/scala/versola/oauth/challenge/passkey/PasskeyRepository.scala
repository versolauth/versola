package versola.oauth.challenge.passkey

import versola.auth.model.{CredentialId, PasskeyRecord}
import versola.user.model.UserId
import zio.Task

import java.time.Instant

trait PasskeyRepository:
  def insert(record: PasskeyRecord): Task[Unit]

  /** Shard-native assertion lookup: the userHandle (== userId) is always returned by the
    * authenticator, so this carries the shard key and needs no global index.
    */
  def findByCredentialIdAndUser(id: CredentialId, userId: UserId): Task[Option[PasskeyRecord]]

  /** Global credentialId uniqueness guard used by the WebAuthn library's `lookupAll`.
    *
    * This is the only operation without a shard key. When `passkeys` is sharded by `user_id`,
    * back it with a global `credentialId -> user_id` routing index (future step).
    */
  def findByCredentialId(id: CredentialId): Task[Vector[PasskeyRecord]]

  def listByUser(userId: UserId): Task[Vector[PasskeyRecord]]

  def updateUsage(id: CredentialId, signatureCounter: Long, lastUsedAt: Instant): Task[Boolean]

  def rename(id: CredentialId, userId: UserId, name: Option[String]): Task[Unit]

  def deleteByUser(id: CredentialId, userId: UserId): Task[Unit]

  def delete(id: CredentialId): Task[Unit]
