package versola.user

import versola.user.model.*
import zio.{Clock, Task, ZIO}

import java.time.{Instant, LocalDate}
import java.util.UUID

trait UserRepository:
  def findOrCreateByEmail(email: Email): Task[(UserRecord, WasCreated)]

  def create(id: UserId): Task[UserRecord]

  def find(id: UserId): Task[Option[UserRecord]]

  def findByEmail(email: Email): Task[Option[UserRecord]]

  def update(
      userId: UserId,
      email: Option[Option[Email]],
      firstName: Option[Option[FirstName]],
      middleName: Option[Option[MiddleName]],
      lastName: Option[Option[LastName]],
      birthDate: Option[Option[BirthDate]],
  ): Task[Unit]

