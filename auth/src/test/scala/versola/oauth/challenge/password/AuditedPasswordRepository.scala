package versola.oauth.challenge.password

import versola.audit.{AuditProbe, AuthAuditKinds}
import versola.oauth.challenge.password.model.{PasswordHistoryChanged, PasswordReuseError}
import versola.user.model.UserId
import versola.util.{Salt, Secret}
import zio.{IO, Task}

import java.time.Instant

// As the specs that are about something else than the audit call the repository: each change is handed a fresh event.
extension (repository: PasswordRepository)
  def create(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
      expectedHead: Option[Long],
  ): IO[Throwable | PasswordReuseError | PasswordHistoryChanged.type, Unit] =
    AuditProbe.event(AuthAuditKinds.AccountPasswordSet, Some(userId)).flatMap(
      repository.create(userId, passwordHash, salt, historySize, numDifferent, expectedHead, _),
    )

  def createTemporary(userId: UserId, passwordHash: Secret, salt: Salt, expiresAt: Instant): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.AccountPasswordSet, Some(userId)).flatMap(repository.createTemporary(userId, passwordHash, salt, expiresAt, _))
