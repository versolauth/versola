package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{IO, Task, ZIO, ZLayer}

trait UserService:
  def findById(id: UserId): Task[Option[UserSearchRecord]]
  def findByEmail(email: Email): Task[Option[UserSearchRecord]]
  def findByPhone(phone: Phone): Task[Option[UserSearchRecord]]
  def findByLogin(login: Login): Task[Option[UserSearchRecord]]

  def getRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

  def getSessions(id: UserId): Task[List[AuthClient.SessionDto]]

  def invalidateSession(userId: UserId): Task[Unit]

  def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId]

  def patch(request: PatchUserRequest): Task[Unit]

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit]

  def updateRoles(request: UpdateUserRolesRequest): Task[Unit]

  def resetLimits(request: ResetUserLimitsRequest): Task[Unit]

object UserService:
  val live: ZLayer[UserRepository & AuthClient & SecureRandom, Nothing, UserService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(userRepository: UserRepository, authClient: AuthClient, secureRandom: SecureRandom) extends UserService:
    override def findById(id: UserId): Task[Option[UserSearchRecord]] =
      userRepository.findById(id).flatMap(enrich)

    override def findByEmail(email: Email): Task[Option[UserSearchRecord]] =
      userRepository.findByEmail(email).flatMap(enrich)

    override def findByPhone(phone: Phone): Task[Option[UserSearchRecord]] =
      userRepository.findByPhone(phone).flatMap(enrich)

    override def findByLogin(login: Login): Task[Option[UserSearchRecord]] =
      userRepository.findByLogin(login).flatMap(enrich)

    private def enrich(record: Option[UserIndexRecord]): Task[Option[UserSearchRecord]] =
      ZIO.foreach(record): r =>
        authClient.getUserClaims(r.id).map: claims =>
          UserSearchRecord(r.id, r.email, r.phone, r.login, claims.getOrElse(Json.Obj()))

    override def getRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]] =
      authClient.getUserRoles(id, tenantId)

    override def getSessions(id: UserId): Task[List[AuthClient.SessionDto]] =
      authClient.getUserSessions(id)

    override def invalidateSession(userId: UserId): Task[Unit] =
      authClient.invalidateSession(userId)

    override def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId] =
      for
        id <- secureRandom.nextUUIDv7.map(UserId(_))
        _ <- userRepository.create(id, request.email, request.phone, request.login)
      yield id

    override def patch(request: PatchUserRequest): Task[Unit] =
      userRepository.patch(request.id, request.email, request.phone, request.login)

    override def patchClaims(id: UserId, patch: Json.Obj): Task[Unit] =
      authClient.patchUserClaims(id, patch)

    override def updateRoles(request: UpdateUserRolesRequest): Task[Unit] =
      authClient.updateUserRoles(request.userId, request.tenantId, request.add, request.remove)

    override def resetLimits(request: ResetUserLimitsRequest): Task[Unit] =
      authClient.resetUserLimits(request.userId, request.tenantId, request.email, request.phone)
