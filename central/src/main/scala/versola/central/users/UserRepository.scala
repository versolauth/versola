package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.{Duration, IO, Task}

import java.util.UUID

/** The directory of users (versolauth/versola#1041) and the outbox that carries central's changes of a user to `auth`.
  * A repository stores what it is given: which identifiers a user may hold, and under which key they are MACed, is
  * the service's to decide.
  */
trait UserRepository:
  def find(id: UserId): Task[Option[UserDirectoryRecord]]

  /** The user an identifier belongs to, in any cell. */
  def findByIdentifier(identifier: DirectoryIdentifier): Task[Option[UserDirectoryRecord]]

  /** The kinds of identifier the user holds. */
  def identifierKinds(id: UserId): Task[Set[IdentifierKind]]

  /** Atomically stores the user with its identifiers and enqueues `event` for `auth`.
    * Fails with [[UserConflict]] when the id or one of the identifiers is already taken.
    */
  def create(
      user: UserDirectoryRecord,
      identifiers: List[DirectoryIdentifier],
      event: OutboxEvent,
      eventId: UUID,
  ): IO[UserConflict | Throwable, Unit]

  /** Claims `identifiers` for `candidate`, a user that does not exist yet, and answers who holds them now: the
    * candidate when none was claimed before, the owner when they already belong to one user. A claim is permanent and
    * repeating it changes nothing, so a caller that stopped half-way claims again and gets the same answer. Nothing is
    * enqueued: the caller is the `auth` that creates the user. Fails with [[UserIndexConflict]] when the identifiers
    * belong to more than one user.
    */
  def claim(identifiers: List[DirectoryIdentifier], candidate: UserDirectoryRecord): IO[UserIndexConflict | Throwable, UserDirectoryRecord]

  /** Atomically gives the user the identifiers of `set` in place of what it held of those kinds, takes away the kinds
    * of `remove`, and enqueues `event`. Fails with [[UserNotFound]] when there is no such user, or [[UserConflict]]
    * when an identifier is held by another user.
    */
  def replaceIdentifiers(
      id: UserId,
      set: List[DirectoryIdentifier],
      remove: Set[IdentifierKind],
      event: OutboxEvent,
      eventId: UUID,
  ): IO[UserNotFound | UserConflict | Throwable, Unit]

  /** Enqueues an UpdateUserRoles outbox event, dispatched to auth in per-user FIFO order.
    * Ordering behind [[create]]/[[replaceIdentifiers]] events guarantees the user exists in auth before roles are applied.
    */
  def enqueueRoleUpdate(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]

  /** Removes the user and its identifiers, which may be claimed again, and enqueues the deletion for `auth`. */
  def delete(id: UserId): Task[Unit]

  /** Atomically claim up to `limit` due outbox rows by leasing them for `lease` (other instances skip them).
    * The lease is reset to a backoff value by [[rescheduleEvent]] on failure, or the row is removed on success.
    */
  def claimDueEvents(limit: Int, lease: Duration): Task[Vector[OutboxRecord]]

  /** Delete an outbox row after successful dispatch. */
  def deleteEvent(id: UUID): Task[Unit]

  /** Increment attempts and reschedule (overrides the lease set by [[claimDueEvents]]). */
  def rescheduleEvent(id: UUID, delay: Duration): Task[Unit]

  /** Move a failed event to the dead letter table and delete it from the outbox.
    * @param id The event ID
    * @param error Error message describing why the event failed
    */
  def moveToDeadLetter(id: UUID, error: String): Task[Unit]
