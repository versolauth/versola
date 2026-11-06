package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{PasswordHistoryChanged, PasswordReuseError}
import versola.user.model.UserId
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
    */
  def create(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
      expectedHead: Option[Long],
  ): IO[Throwable | PasswordReuseError | PasswordHistoryChanged.type, Unit]

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

  /** Creates the bootstrap admin's temporary password and, when `linkTokenHash` is given, its
    * first-login token, but only if the user has no usable credential (a permanent password or a
    * temporary one that has not expired). The check and the inserts run under the per-user lock, so
    * of several auth replicas starting together exactly one gets `true`, and only that one prints a
    * link. Replaces any earlier token of the user.
    */
  def createBootstrapCredential(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
      linkTokenHash: Option[Array[Byte]],
  ): Task[Boolean]

  /** Deletes the user's temporary password and first-login token so that the next bootstrap issues new
    * ones. Refused (`false`, nothing deleted) when the user has a permanent password: reset exists for
    * an admin who has not finished the first login, never as a way around a password that is set.
    */
  def resetBootstrapCredential(userId: UserId): Task[Boolean]

  /** Spends a one-time link: deletes the row and returns whose it was and what it was for when the hash matches
    * an unexpired link of a purpose this release knows, `None` otherwise. One statement, so two concurrent opens
    * of the same link cannot both succeed. A `first_login` link is also refused for a user who has a permanent
    * password.
    */
  def consumeLink(tokenHash: Array[Byte]): Task[Option[ConsumedLink]]
