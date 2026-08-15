package versola.central.users

import versola.central.configuration.clients.{ClientId, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{Duration, IO, Task, ZIO, ZLayer, duration2DurationOps}

import java.time.Instant
import scala.util.Try

trait UserService:
  def findById(id: UserId): Task[Option[UserSearchRecord]]
  def findByEmail(email: Email): Task[Option[UserSearchRecord]]
  def findByPhone(phone: Phone): Task[Option[UserSearchRecord]]
  def findByLogin(login: Login): Task[Option[UserSearchRecord]]

  def getRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

  def getSessions(id: UserId): Task[List[SessionResponse]]

  def invalidateSession(userId: UserId): Task[Unit]

  def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId]

  /** Claim credentials and return the canonical ID for self-service registration. */
  def indexRegistered(request: RegisteredUserRequest): IO[UserIndexConflict | Throwable, UserId]

  def patch(request: PatchUserRequest): Task[Unit]

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit]

  def delete(id: UserId): Task[Unit]

  def updateRoles(request: UpdateUserRolesRequest): Task[Unit]

  def resetLimits(request: ResetUserLimitsRequest): Task[Unit]

  def listPasskeys(userId: UserId): Task[List[PasskeyInfo]]

  def renamePasskey(request: RenamePasskeyRequest): Task[Unit]

  def deletePasskey(userId: UserId, credentialId: String): Task[Unit]

  def resetPassword(request: ResetPasswordRequest): Task[Unit]

  def setPassword(userId: UserId, password: String): Task[Unit]

object UserService:
  val live: ZLayer[UserRepository & AuthClient & OAuthClientService & SecureRandom, Nothing, UserService] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      userRepository: UserRepository,
      authClient: AuthClient,
      oAuthClientService: OAuthClientService,
      secureRandom: SecureRandom,
  ) extends UserService:
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

    override def getSessions(id: UserId): Task[List[SessionResponse]] =
      for
        sessions <- authClient.getUserSessions(id)
        clients <- oAuthClientService.getAllClients
        ttlByClientId = clients.map(c => c.id -> c.accessTokenTtl).toMap
      yield sessions.map(toSessionResponse(_, ttlByClientId))

    private def toSessionResponse(
        session: AuthClient.SessionDto,
        ttlByClientId: Map[ClientId, Duration],
    ): SessionResponse =
      SessionResponse(
        publicId = session.publicId,
        clients = session.clients
          .flatMap { entry =>
            ttlByClientId.get(entry.clientId)
              .map { duration =>
                ClientSessionEntry(
                  entry.clientId,
                  entry.enteredAt,
                  entry.enteredAt.plus(duration.asJava),
                )
              }
          }
          .sortBy(_.enteredAt)(using Ordering[Instant].reverse),
        platform = session.platform,
        os = session.os,
        browser = session.browser,
        version = session.version,
        createdAt = session.createdAt,
      )

    override def invalidateSession(userId: UserId): Task[Unit] =
      authClient.invalidateSession(userId)

    override def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId] =
      for
        id <- secureRandom.nextUUIDv7.map(UserId(_))
        _ <- userRepository.create(id, request.email, request.phone, request.login)
      yield id

    override def indexRegistered(request: RegisteredUserRequest): IO[UserIndexConflict | Throwable, UserId] =
      userRepository.indexFromAuth(request.email, request.phone, request.login)

    override def patch(request: PatchUserRequest): Task[Unit] =
      userRepository.patch(request.id, request.email, request.phone, request.login)

    override def patchClaims(id: UserId, patch: Json.Obj): Task[Unit] =
      authClient.patchUserClaims(id, patch)

    override def delete(id: UserId): Task[Unit] =
      userRepository.delete(id)

    override def updateRoles(request: UpdateUserRolesRequest): Task[Unit] =
      userRepository.enqueueRoleUpdate(request.userId, request.tenantId, request.add, request.remove)

    override def resetLimits(request: ResetUserLimitsRequest): Task[Unit] =
      authClient.resetUserLimits(request.userId, request.tenantId, request.email, request.phone)

    override def listPasskeys(userId: UserId): Task[List[PasskeyInfo]] =
      authClient.listPasskeys(userId)

    override def renamePasskey(request: RenamePasskeyRequest): Task[Unit] =
      authClient.renamePasskey(request.userId, request.credentialId, request.name)

    override def deletePasskey(userId: UserId, credentialId: String): Task[Unit] =
      authClient.deletePasskey(userId, credentialId)

    override def resetPassword(request: ResetPasswordRequest): Task[Unit] =
      authClient.resetPassword(request)

    override def setPassword(userId: UserId, password: String): Task[Unit] =
      authClient.setPassword(userId, password)
