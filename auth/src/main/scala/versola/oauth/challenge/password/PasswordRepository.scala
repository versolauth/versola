package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.user.model.UserId
import versola.util.{Salt, Secret}
import zio.{IO, Task}

import java.time.Instant

trait PasswordRepository:
  def list(userId: UserId): Task[Vector[PasswordRecord]]

  /** Creates a permanent password, evicting the oldest history entry and checking reuse.
    * Deletes any temporary passwords for the user before inserting.
    */
  def create(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
  ): IO[Throwable | PasswordReuseError, Unit]

  /** Creates a temporary password (not counted in history, not checked for reuse).
    * Deletes any existing temporary passwords for the user before inserting.
    */
  def createTemporary(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
  ): Task[Unit]

  /** Deletes all temporary passwords for the user. */
  def deleteTemporary(userId: UserId): Task[Unit]
