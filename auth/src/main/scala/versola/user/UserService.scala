package versola.user

import versola.user.model.{BirthDate, FieldName, FirstName, LastName, MiddleName, PatchUserErrorResponse, PatchUserRequest, UserId, UserNotFound, UserResponse}
import zio.*

trait UserService:
  def getProfile(
      userId: UserId,
  ): IO[Throwable | UserNotFound, UserResponse]

  def updateProfile(
      userId: UserId,
      request: PatchUserRequest,
  ): IO[Throwable, PatchUserErrorResponse]



object UserService:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(userRepository: UserRepository) extends UserService:
    
    override def getProfile(userId: UserId): IO[Throwable | UserNotFound, UserResponse] =
      userRepository.find(userId)
        .someOrFail(UserNotFound(userId))
        .map(UserResponse.from)

    override def updateProfile(
        userId: UserId,
        request: PatchUserRequest,
    ): IO[Throwable, PatchUserErrorResponse] = {
      userRepository.find(userId).flatMap:
        case None =>
          ZIO.succeed(PatchUserErrorResponse.empty)
        case Some(user) =>
          for
            (firstNameError, firstNameUpdate) = validated(request, FieldName.firstName, FirstName.from)
            (middleNameError, middleNameUpdate) = validated(request, FieldName.middleName, MiddleName.from)
            (lastNameError, lastNameUpdate) = validated(request, FieldName.lastName, LastName.from)
            (birthDateError, birthDateUpdate) = validated(request, FieldName.birthDate, BirthDate.from)

            errors = PatchUserRequest.UpdateFields(
              firstName = firstNameError,
              middleName = middleNameError,
              lastName = lastNameError,
              birthDate = birthDateError,
            )

            _ <- userRepository.update(
              userId = userId,
              email = None, // Email updates not supported through this endpoint
              firstName = firstNameUpdate,
              middleName = middleNameUpdate,
              lastName = lastNameUpdate,
              birthDate = birthDateUpdate,
            )
          yield PatchUserErrorResponse(errors)
    }

    private def validated[A](
        request: PatchUserRequest,
        fieldName: FieldName,
        validator: String => Either[String, A],
    ): (error: Option[String], update: Option[Option[A]]) =
      if request.delete(fieldName) then
        (error = None, update = Some(None))
      else
        request.updateFields.get(fieldName) match
          case None =>
            (error = None, update = None)

          case Some(value) =>
            val validate = validator(value)
            (error = validate.left.toOption, update = validate.toOption.map(Some(_)))


