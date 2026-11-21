package versola.central.users

import versola.central.configuration.clients.{ClientId, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.{CellPlacement, TopologyRepository}
import versola.util.{Email, Patch, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{Duration, IO, Random, Task, ZIO, ZLayer, duration2DurationOps}

import java.time.Instant
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
  def indexRegistered(request: RegisteredUserRequest): IO[UserIndexConflict | Throwable, RegisteredUserResponse]

  /** The user that holds the identifiers and the cell that holds the user, without claiming anything; `None` when
    * nobody holds them, or when they belong to more than one user (which a claim reports, not a lookup). */
  def lookup(request: LookupUserRequest): Task[Option[RegisteredUserResponse]]

  def patch(request: PatchUserRequest): IO[UserNotFound | UserConflict | Throwable, Unit]

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
  /** What `auth` holds of a user once `event` has been delivered to it. */
  private[users] def applied(user: Option[AuthClient.UserDto], event: OutboxEvent): Option[AuthClient.UserDto] =
    def patched[A](current: Option[A], patch: Option[Patch[A]]): Option[A] = patch match
      case None => current
      case Some(Patch.Deleted) => None
      case Some(Patch.Modified(value)) => Some(value)
    event match
      case e: OutboxEvent.UpsertUser =>
        Some(AuthClient.UserDto(email = e.email, phone = e.phone, login = e.login, claims = user.fold(Json.Obj())(_.claims)))
      case e: OutboxEvent.PatchUser =>
        user.map(u => u.copy(email = patched(u.email, e.email), phone = patched(u.phone, e.phone), login = patched(u.login, e.login)))
      case _: OutboxEvent.DeleteUser => None
      case _: OutboxEvent.UpdateUserRoles => user

  val live: ZLayer[UserRepository & AuthClient & OAuthClientService & SecureRandom & DirectoryKey & TopologyRepository, Nothing, UserService] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      userRepository: UserRepository,
      authClient: AuthClient,
      oAuthClientService: OAuthClientService,
      secureRandom: SecureRandom,
      directoryKey: DirectoryKey,
      topology: TopologyRepository,
  ) extends UserService:
    override def findById(id: UserId): Task[Option[UserSearchRecord]] =
      userRepository.find(id).flatMap(enrich)

    override def findByEmail(email: Email): Task[Option[UserSearchRecord]] =
      userRepository.findByIdentifier(directoryKey.email(email)).flatMap(enrich)

    override def findByPhone(phone: Phone): Task[Option[UserSearchRecord]] =
      userRepository.findByIdentifier(directoryKey.phone(phone)).flatMap(enrich)

    override def findByLogin(login: Login): Task[Option[UserSearchRecord]] =
      userRepository.findByIdentifier(directoryKey.login(login)).flatMap(enrich)

    /** The directory says that the user exists; what its identifiers are is read from `auth`, which holds them, with
      * the changes central has queued for `auth` and not delivered yet applied on top: a user is read back as it was
      * just written, although central keeps no copy of what was written. */
    private def enrich(record: Option[UserDirectoryRecord]): Task[Option[UserSearchRecord]] =
      ZIO.foreach(record): r =>
        for
          held <- authClient.getUser(r.id)
          pending <- userRepository.pendingEvents(r.id)
        yield
          val user = pending.foldLeft(held)(UserService.applied)
          UserSearchRecord(
            id = r.id,
            email = user.flatMap(_.email),
            phone = user.flatMap(_.phone),
            login = user.flatMap(_.login),
            claims = user.fold(Json.Obj())(_.claims),
          )

    private def identifiers(email: Option[Email], phone: Option[Phone], login: Option[Login]): List[DirectoryIdentifier] =
      List(email.map(directoryKey.email), phone.map(directoryKey.phone), login.map(directoryKey.login)).flatten

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
        version <- secureRandom.nextUUIDv7
        // Placed like a registration is, by the weights of the open cells; with no cell that takes users the user is in
        // the `auth` central is configured with. The outbox then carries the user to the cell it was placed in.
        cells <- topology.getCells
        roll <- Random.nextDouble
        _ <- userRepository.create(
          user = UserDirectoryRecord(id, cellId = CellPlacement.pick(cells, roll)),
          identifiers = identifiers(request.email, request.phone, request.login),
          event = OutboxEvent.UpsertUser(userId = id, version = version, email = request.email, phone = request.phone, login = request.login),
          eventId = version,
        )
      yield id

    override def indexRegistered(request: RegisteredUserRequest): IO[UserIndexConflict | Throwable, RegisteredUserResponse] =
      for
        id <- secureRandom.nextUUIDv7.map(UserId(_))
        // The cell of a new claim is the caller's: the user is created where the registration was taken. An identifier
        // claimed before answers its owner and the owner's cell, whatever cell asks.
        owner <- userRepository.claim(
          identifiers = identifiers(request.email, request.phone, request.login),
          candidate = UserDirectoryRecord(id, cellId = request.cell),
        )
      yield RegisteredUserResponse(userId = owner.id, cell = owner.cellId)

    override def lookup(request: LookupUserRequest): Task[Option[RegisteredUserResponse]] =
      ZIO.foreach(identifiers(request.email, request.phone, request.login))(userRepository.findByIdentifier).map: owners =>
        owners.flatten.distinctBy(_.id) match
          case owner :: Nil => Some(RegisteredUserResponse(userId = owner.id, cell = owner.cellId))
          case _ => None

    override def patch(request: PatchUserRequest): IO[UserNotFound | UserConflict | Throwable, Unit] =
      def change[A](kind: IdentifierKind, patch: Option[Patch[A]])(mac: A => DirectoryIdentifier) =
        patch.map:
          case Patch.Modified(value) => kind -> Some(mac(value))
          case Patch.Deleted => kind -> None
      val changes = List(
        change(IdentifierKind.Email, request.email)(directoryKey.email),
        change(IdentifierKind.Phone, request.phone)(directoryKey.phone),
        change(IdentifierKind.Login, request.login)(directoryKey.login),
      ).flatten
      for
        version <- secureRandom.nextUUIDv7
        _ <- userRepository.replaceIdentifiers(
          id = request.id,
          set = changes.flatMap(_._2),
          remove = changes.collect { case (kind, None) => kind }.toSet,
          event = OutboxEvent.PatchUser(userId = request.id, version = version, email = request.email, phone = request.phone, login = request.login),
          eventId = version,
        )
      yield ()

    override def patchClaims(id: UserId, patch: Json.Obj): Task[Unit] =
      authClient.patchUserClaims(id, patch)

    override def delete(id: UserId): IO[UserNotFound | Throwable, Unit] =
      for
        _ <- userRepository.find(id).someOrFail(UserNotFound)
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
