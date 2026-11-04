package versola.oauth.challenge.passkey

import versola.audit.{AuditProbe, AuthAuditKinds}
import versola.auth.model.{CredentialId, PasskeyRecord}
import versola.user.model.UserId
import zio.Task

// As the specs that are about something else than the audit call the repository: each change is handed a fresh event.
extension (repository: PasskeyRepository)
  def insert(record: PasskeyRecord): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.AccountPasskeyEnrolled, Some(record.userId)).flatMap(repository.insert(record, _))

  def deleteByUser(id: CredentialId, userId: UserId): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.AccountPasskeyRemoved, Some(userId)).flatMap(repository.deleteByUser(id, userId, _))
