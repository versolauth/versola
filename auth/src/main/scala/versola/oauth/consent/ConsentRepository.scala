package versola.oauth.consent

import versola.oauth.client.model.ClientId
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import versola.util.audit.AuditEvent
import zio.Task

trait ConsentRepository:
  def find(userId: UserId, clientId: ClientId): Task[Option[ConsentRecord]]

  /** Records the grant, replacing any previous grant for the same (user, client) pair. `audit` (`consent.granted`) is inserted in the
    * same transaction. */
  def upsert(record: ConsentRecord, audit: AuditEvent): Task[Unit]

  /** `audit` (`consent.revoked`) is inserted in the same transaction, only when a consent was removed. */
  def delete(userId: UserId, clientId: ClientId, audit: AuditEvent): Task[Unit]
