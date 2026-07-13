package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.{Duration, IO, Task}

import java.util.UUID

trait UserRepository:
  def findById(id: UserId): Task[Option[UserIndexRecord]]
  def findByEmail(email: Email): Task[Option[UserIndexRecord]]
  def findByPhone(phone: Phone): Task[Option[UserIndexRecord]]
  def findByLogin(login: Login): Task[Option[UserIndexRecord]]

  /** Atomically inserts/updates the index row and enqueues a UpsertUser outbox event.
    * Fails with [[UserConflict]] when the email/phone/login violates a unique constraint.
    */
  def create(
      id: UserId,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ): IO[UserConflict | Throwable, Unit]

  /** Atomically patches index columns and enqueues a UpsertUser outbox event.
    * Each `Option[Patch[A]]` follows three-state semantics: `None` keeps the column,
    * `Some(Patch.Deleted)` sets it to NULL, `Some(Patch.Modified(v))` sets it to `v`.
    */
  def patch(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
  ): Task[Unit]

  /** Enqueues an UpdateUserRoles outbox event, dispatched to auth in per-user FIFO order.
    * Ordering behind [[create]]/[[patch]] events guarantees the user exists in auth before roles are applied.
    */
  def enqueueRoleUpdate(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]

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
