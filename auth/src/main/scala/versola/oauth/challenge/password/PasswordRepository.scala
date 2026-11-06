package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{PasswordChangeEffect, PasswordHistoryChanged, PasswordReuseError}
import versola.oauth.revocation.RevocationTerms
import versola.oauth.session.model.SessionRecord
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
    *
    * `change` is applied in the same transaction (see [[PasswordChangeEffect]]); the sessions it ended come
    * back, for the caller to tell their relying parties.
    */
  def create(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
      expectedHead: Option[Long],
      change: PasswordChangeEffect,
  ): IO[Throwable | PasswordReuseError | PasswordHistoryChanged.type, List[SessionRecord]]

  /** Creates a temporary password (not counted in history, not checked for reuse).
    * Deletes any existing temporary passwords for the user before inserting.
    *
    * A temporary password is set by an administrator's reset (`refuseEarlierTokens` is then the terms of
    * [[PasswordChangeEffect.RefuseEarlierTokens]], recorded in this transaction) or by the bootstrap
    * (`None`); it is never the user's own change, so there are no sessions of theirs to keep or end.
    */
  def createTemporary(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
      refuseEarlierTokens: Option[RevocationTerms],
  ): Task[Unit]

  /** Deletes all temporary passwords for the user. */
  def deleteTemporary(userId: UserId): Task[Unit]
