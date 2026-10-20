package versola.central.users

import versola.central.configuration.clients.{ClientId, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{Duration, IO, Task, ZIO, ZLayer, duration2DurationOps}

import java.time.Instant
import java.util.UUID
import scala.util.Try

trait UserService:
  def findById(id: UserId): Task[Option[UserSearchRecord]]
  def findByEmail(email: Email): Task[Option[UserSearchRecord]]
  def findByPhone(phone: Phone): Task[Option[UserSearchRecord]]
  def findByLogin(login: Login): Task[Option[UserSearchRecord]]

  def getRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

  def getSessions(id: UserId): Task[List[SessionResponse]]

  /** Every remembered device of the user -- user agent, and the live session under it or `None` once
    * signed out -- as auth stores them. */
  def getDevices(id: UserId): Task[List[DeviceResponse]]

  /** Forgets a device: its user agent, session and the refresh tokens bound to that session.
    * `false` when the user agent is not the user's. */
  def forgetUserAgent(userId: UserId, userAgentId: String): Task[Boolean]

  def invalidateSession(userId: UserId): Task[Unit]

  /** Signs one user agent out: ends that session of the user, relying parties told. `false`
    * when the session is not the user's or no longer live. */
  def endSession(userId: UserId, sessionId: String): Task[Boolean]

  /** Revokes the offline access one client holds for the user. The user's sessions stay. */
  def revokeRefreshTokens(userId: UserId, clientId: ClientId): Task[Unit]

  /** Every currently-live refresh token issued to the user, independent of
    * [[getSessions]]: a refresh token's expiry is fixed at issuance and independent of a
    * session's, so a token routinely outlives the session it was issued under. */
  def getRefreshTokens(id: UserId): Task[List[RefreshTokenResponse]]

  def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId]

  /** Claim credentials and return the canonical ID for self-service registration. */
  def indexRegistered(request: RegisteredUserRequest): IO[UserIndexConflict | Throwable, UserId]

  def patch(request: PatchUserRequest): IO[UserNotFound | UserConflict | Throwable, Unit]

  /** Replaces the email of an indexed user and enqueues the auth upsert; returns the outbox event version. */
  def setEmail(userId: UserId, email: Email): IO[UserNotFound | UserConflict | Throwable, UUID]

  /** Replaces the phone of an indexed user and enqueues the auth upsert; returns the outbox event version. */
  def setPhone(userId: UserId, phone: Phone): IO[UserNotFound | UserConflict | Throwable, UUID]

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit]

  /** Hard-deletes the user: everything auth holds for them first -- sessions, refresh tokens,
    * passkeys, passwords, roles, throttling, pending conversations -- then the index row. Fails
    * with [[UserNotFound]] when the id is not indexed, and leaves the index row in place when
    * auth's purge fails, so the deletion can be retried rather than leaving a user nothing can
    * find but who can still sign in. */
  def delete(id: UserId): IO[UserNotFound | Throwable, Unit]

  def updateRoles(request: UpdateUserRolesRequest): Task[Unit]

  def resetLimits(request: ResetUserLimitsRequest): Task[Unit]

  def listPasskeys(userId: UserId): Task[List[PasskeyInfo]]

  def renamePasskey(request: RenamePasskeyRequest): Task[Unit]

  def deletePasskey(userId: UserId, credentialId: String): Task[Unit]

  def resetPassword(request: ResetPasswordRequest): Task[Option[String]]

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

    override def getDevices(id: UserId): Task[List[DeviceResponse]] =
      for
        devices <- authClient.getUserDevices(id)
        clients <- oAuthClientService.getAllClients
        ttlByClientId = clients.map(c => c.id -> c.accessTokenTtl).toMap
      yield devices.map: device =>
        DeviceResponse(
          userAgent = device.userAgent.map(toUserAgentResponse),
          session = device.session.map(toSessionResponse(_, ttlByClientId)),
          endedSessionIds = device.endedSessionIds,
        )

    override def forgetUserAgent(userId: UserId, userAgentId: String): Task[Boolean] =
      authClient.forgetUserAgent(userId, userAgentId)

    private def toUserAgentResponse(agent: AuthClient.UserAgentDto): UserAgentResponse =
      UserAgentResponse(
        id = agent.id,
        userAgent = agent.userAgent,
        platform = agent.platform,
        os = agent.os,
        browser = agent.browser,
        version = agent.version,
        createdAt = agent.createdAt,
        expiresAt = agent.expiresAt,
      )

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
        expiresAt = session.expiresAt,
        idleExpiresAt = session.idleExpiresAt,
        userAgent = session.userAgent.map(toUserAgentResponse),
      )

    override def invalidateSession(userId: UserId): Task[Unit] =
      authClient.invalidateSession(userId)

    override def endSession(userId: UserId, sessionId: String): Task[Boolean] =
      authClient.endSession(userId, sessionId)

    override def revokeRefreshTokens(userId: UserId, clientId: ClientId): Task[Unit] =
      authClient.revokeRefreshTokens(userId, clientId)

    override def getRefreshTokens(id: UserId): Task[List[RefreshTokenResponse]] =
      authClient.getUserRefreshTokens(id).map(_.map { token =>
        RefreshTokenResponse(
          sessionId = token.sessionId,
          clientId = token.clientId,
          scope = token.scope,
          issuedAt = token.issuedAt,
          expiresAt = token.expiresAt,
          dpopBound = token.dpopBound,
        )
      })

    override def create(request: CreateUserRequest): IO[UserConflict | Throwable, UserId] =
      for
        id <- secureRandom.nextUUIDv7.map(UserId(_))
        _ <- userRepository.create(id, request.email, request.phone, request.login)
      yield id

    override def indexRegistered(request: RegisteredUserRequest): IO[UserIndexConflict | Throwable, UserId] =
      userRepository.indexFromAuth(request.email, request.phone, request.login)

    override def patch(request: PatchUserRequest): IO[UserNotFound | UserConflict | Throwable, Unit] =
      userRepository.patch(request.id, request.email, request.phone, request.login).unit

    override def setEmail(userId: UserId, email: Email): IO[UserNotFound | UserConflict | Throwable, UUID] =
      userRepository.patch(userId, Some(Patch.Modified(email)), None, None)

    override def setPhone(userId: UserId, phone: Phone): IO[UserNotFound | UserConflict | Throwable, UUID] =
      userRepository.patch(userId, None, Some(Patch.Modified(phone)), None)

    override def patchClaims(id: UserId, patch: Json.Obj): Task[Unit] =
      authClient.patchUserClaims(id, patch)

    override def delete(id: UserId): IO[UserNotFound | Throwable, Unit] =
      for
        _ <- userRepository.findById(id).someOrFail(UserNotFound)
        _ <- authClient.deleteUser(id)
        // The index row goes with a `DeleteUser` event queued behind it: the outbox delivers
        // per user in order, so an upsert still in flight for this user is followed by a
        // second, idempotent purge rather than resurrecting the account in auth.
        _ <- userRepository.delete(id)
      yield ()

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

    override def resetPassword(request: ResetPasswordRequest): Task[Option[String]] =
      authClient.resetPassword(request.userId, request.expiresInSeconds, request.channel)

    override def setPassword(userId: UserId, password: String): Task[Unit] =
      authClient.setPassword(userId, password)
