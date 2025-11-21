package versola.user.model

import zio.schema.*
import zio.stream.UStream

import java.time.Instant

case class UserResponse(
    email: Option[Email],
    firstName: Option[FirstName],
    middleName: Option[MiddleName],
    lastName: Option[LastName],
    birthDate: Option[BirthDate],
    createdAt: Instant
) derives Schema

object UserResponse:
  def from(user: UserRecord): UserResponse =
    UserResponse(
      email = user.email,
      firstName = user.firstName,
      middleName = user.middleName,
      lastName = user.lastName,
      birthDate = user.birthDate,
      createdAt = user.createdAt
    )

case class PatchUserRequest(
    delete: Set[FieldName],
    update: PatchUserRequest.UpdateFields,
) derives Schema:
  val updateFields: Map[FieldName, String] =
    Map(
      FieldName.firstName -> update.firstName,
      FieldName.middleName -> update.middleName,
      FieldName.lastName -> update.lastName,
      FieldName.birthDate -> update.birthDate,
    ).collect { case (k, Some(v)) => (k, v) }

object PatchUserRequest:
  val empty = PatchUserRequest(Set.empty, UpdateFields.empty)
  case class UpdateFields(
      firstName: Option[String],
      middleName: Option[String],
      lastName: Option[String],
      birthDate: Option[String],
  ) derives Schema

  object UpdateFields:
    val empty = UpdateFields(
      firstName = None,
      middleName = None,
      lastName = None,
      birthDate = None,
    )

case class PatchUserErrorResponse(
    errors: PatchUserRequest.UpdateFields,
) derives Schema

object PatchUserErrorResponse:
  val empty: PatchUserErrorResponse = PatchUserErrorResponse(PatchUserRequest.UpdateFields.empty)

enum FieldName derives Schema:
  case firstName, middleName, lastName, birthDate
