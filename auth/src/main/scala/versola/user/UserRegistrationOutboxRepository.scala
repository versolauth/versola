package versola.user

import versola.user.model.UserId
import zio.{Duration, Task}

import java.util.UUID

/** Queue of accounts created by self-service registration awaiting delivery to central.
  *
  * Registration writes the account and its outbox row in one transaction, so an account is
  * never created without central eventually hearing about it, and a dispatch that fails
  * midway is retried rather than lost.
  */
trait UserRegistrationOutboxRepository:
  def claimDueEvents(limit: Int, lease: Duration): Task[Vector[UserRegistrationOutboxRecord]]

  def deleteEvent(id: UUID): Task[Unit]

  def rescheduleEvent(id: UUID, delay: Duration): Task[Unit]

  def moveToDeadLetter(id: UUID, error: String): Task[Unit]

case class UserRegistrationOutboxRecord(
    id: UUID,
    userId: UserId,
    event: UserRegisteredEvent,
    attempts: Int,
)
