package versola.user

import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserRecord
import versola.util.{Email, Phone}
import zio.*

trait UserService:
  /**
    * Resolve the credential to an account for registration.
    *
    * Central is the sole allocator of user IDs: it claims the credential and returns the
    * canonical ID, minting one on first claim. The configured tenant roles are assigned to the
    * resolved account regardless of whether it was just created; reaching this operation already
    * means the registration flow matched.
    */
  def register(
      credential: Either[Email, Phone],
      tenantId: TenantId,
      roleIds: Set[RoleId],
  ): Task[UserRecord]

object UserService:
  def live: ZLayer[UserRepository & UserRegistrationSyncClient, Nothing, UserService] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      userRepository: UserRepository,
      registrationSyncClient: UserRegistrationSyncClient,
  ) extends UserService:
    override def register(
        credential: Either[Email, Phone],
        tenantId: TenantId,
        roleIds: Set[RoleId],
    ): Task[UserRecord] =
      val claim = credential match
        case Left(email) => UserRegisteredEvent(Some(email), None, None)
        case Right(phone) => UserRegisteredEvent(None, Some(phone), None)
      for
        userId <- registrationSyncClient.claimRegistration(claim)
        user <- userRepository.register(userId, credential, tenantId, roleIds)
      yield user
