package versola.central.users

import versola.central.configuration.roles.{RoleId, RoleService}
import versola.central.configuration.tenants.TenantId
import versola.central.{AdminClaims, CentralConfig}
import versola.util.{Email, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{IO, Task, ZIO, ZLayer}

trait UserService:
  def findById(id: UserId): Task[Option[UserSearchRecord]]
  def findByEmail(email: Email): Task[Option[UserSearchRecord]]
  def findByPhone(phone: Phone): Task[Option[UserSearchRecord]]
  def findByLogin(login: Login): Task[Option[UserSearchRecord]]

  def getMyPermissions(claims: AdminClaims): Task[MyPermissionsResponse]

  def getRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

  def getSessions(id: UserId): Task[List[AuthClient.SessionDto]]

  def invalidateSession(userId: UserId): Task[Unit]

  def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId]

  def patch(request: PatchUserRequest): Task[Unit]

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit]

  def updateRoles(request: UpdateUserRolesRequest): Task[Unit]

  def resetLimits(request: ResetUserLimitsRequest): Task[Unit]

  def listPasskeys(userId: UserId): Task[List[PasskeyInfo]]

  def renamePasskey(request: RenamePasskeyRequest): Task[Unit]

  def deletePasskey(userId: UserId, credentialId: String): Task[Unit]

object UserService:
  val live: ZLayer[UserRepository & AuthClient & SecureRandom & RoleService, Nothing, UserService] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(userRepository: UserRepository, authClient: AuthClient, secureRandom: SecureRandom, roleService: RoleService) extends UserService:
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

    override def getMyPermissions(claims: AdminClaims): Task[MyPermissionsResponse] =
      val adminRoles       = claims.adminRoles.getOrElse(Map.empty)
      val isCentralClient  = claims.clientId.contains(CentralConfig.centralClientId)
      val isSuperAdmin     = isCentralClient && adminRoles.get(TenantId.global).exists(_.contains("oauth-admin"))
      if isSuperAdmin then ZIO.succeed(MyPermissionsResponse(superAdmin = true, roles = None, permissions = None))
      else if !isCentralClient then
        ZIO.succeed(MyPermissionsResponse(superAdmin = false, roles = Some(Set.empty), permissions = Some(Set.empty)))
      else
        val roleIds = adminRoles.getOrElse(CentralConfig.defaultTenantId, Nil).map(RoleId(_)).toSet
        roleService
          .getPermissionsForRoles(CentralConfig.defaultTenantId, roleIds)
          .map: perms =>
            MyPermissionsResponse(
              superAdmin = false,
              roles = Some(roleIds.map(r => r: String)),
              permissions = Some(perms.map(p => p: String)),
            )

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

    override def listPasskeys(userId: UserId): Task[List[PasskeyInfo]] =
      authClient.listPasskeys(userId)

    override def renamePasskey(request: RenamePasskeyRequest): Task[Unit] =
      authClient.renamePasskey(request.userId, request.credentialId, request.name)

    override def deletePasskey(userId: UserId, credentialId: String): Task[Unit] =
      authClient.deletePasskey(userId, credentialId)
