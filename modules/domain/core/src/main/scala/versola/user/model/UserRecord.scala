package versola.user.model

import java.time.{Instant, LocalDate}

case class UserRecord(
    id: UserId,
    email: Option[Email],
    firstName: Option[FirstName],
    middleName: Option[MiddleName],
    lastName: Option[LastName],
    birthDate: Option[BirthDate],
    updatedAt: Instant,
):
  def createdAt: Instant = id.createdAt

object UserRecord:
  def created(id: UserId, email: Email): UserRecord =
    UserRecord(
      id = id,
      email = Some(email),
      firstName = None,
      middleName = None,
      lastName = None,
      birthDate = None,
      updatedAt = id.createdAt,
    )

  def created(id: UserId): UserRecord =
    UserRecord(
      id = id,
      email = None,
      firstName = None,
      middleName = None,
      lastName = None,
      birthDate = None,
      updatedAt = id.createdAt,
    )
