package versola.oauth.consent

import versola.audit.{AuditProbe, AuthAuditKinds}
import versola.oauth.client.model.ClientId
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import zio.Task

// As the specs that are about something else than the audit call the repository: each change is handed a fresh event.
extension (repository: ConsentRepository)
  def upsert(record: ConsentRecord): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.ConsentGranted, Some(record.userId)).flatMap(repository.upsert(record, _))

  def delete(userId: UserId, clientId: ClientId): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.ConsentRevoked, Some(userId)).flatMap(repository.delete(userId, clientId, _))
