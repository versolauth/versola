package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{PasswordHistoryChanged, PasswordReuseError}
import versola.user.model.UserId
import versola.util.audit.AuditEvent
import versola.util.{Salt, Secret}
import zio.{IO, Task}

import java.time.Instant

trait PasswordRepository:
  def list(userId: UserId): Task[Vector[PasswordRecord]]

  /** Creates a permanent password, evicting the oldest history entry and checking reuse.
    * Deletes any temporary passwords for the user before inserting.
    *
    * `expectedHead` is the id of the newest permanent password the caller's own reuse check ran against
    * (`None` for a user without one). Under the per-user lock the insert is refused with
    * [[PasswordHistoryChanged]] if the newest is now another, so a check made outside the transaction
    * still holds when the row goes in.
    *
    * `audit` is inserted in the same transaction, only when the password was written (not when the history changed or the
    * insert is refused), so the event and the change stand or fall together.
    */
  def create(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
      expectedHead: Option[Long],
      audit: AuditEvent,
  ): IO[Throwable | PasswordReuseError | PasswordHistoryChanged.type, Unit]

  /** Creates a temporary password (not counted in history, not checked for reuse).
    * Deletes any existing temporary passwords for the user before inserting. `audit` is inserted in the same transaction.
    */
  def createTemporary(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
      audit: AuditEvent,
  ): Task[Unit]

  /** Deletes all temporary passwords for the user. */
  def deleteTemporary(userId: UserId): Task[Unit]
