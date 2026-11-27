package versola.central.users

import versola.central.configuration.clients.{ClientId, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.{CellId, CellPlacement, CellRecord, CellState, TopologyRepository}
import versola.util.{Email, Patch, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{Duration, IO, Random, Task, ZIO, ZLayer, duration2DurationOps}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

  /** Passes on to the cell of the session's user that a sign-in finished in another cell took the session's place
    * (versolauth/versola#1048). `false` when that cell does not know the session as this user's. */
  def supersedeSession(request: SupersededSessionRequest): Task[Boolean]

  /** Revokes the offline access one client holds for the user. The user's sessions stay. */
  def revokeRefreshTokens(userId: UserId, clientId: ClientId): IO[UserMoving | Throwable, Unit]

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

  /** The cell a sign-in by this login, by this user (the user a passkey names) or by this email or phone number is to
    * be sent to; `None` when there is nowhere to send it. Exactly one of the four is given.
    *
    * A login nobody holds answers a cell all the same, when cells are registered: the one a keyed hash of the login
    * picks, always the same for that login. A cell that sends a sign-in on when the login is held elsewhere, and not
    * when it is held nowhere, would say which logins exist to whoever submits them; this way both are sent on, and the
    * cell that receives one answers it as it answers any login it does not hold. A user id is not guessed, so an
    * unknown one answers nothing.
    *
    * An email or phone number nobody holds is sent to the cell [[UserService.placementCell]] picks by weight (versolauth/
    * versola#1052): the cell it is sent to may register it, so unlike a login it follows the cells' weights, and it
    * answers nothing only when no cell takes users.
    */
  def homeCell(request: HomeCellRequest): Task[Option[CellId]]

  /** The writes below that change what travels with a user (identifiers, claims, roles, passkeys, password, refresh tokens, and the
    * user itself) fail with [[UserMoving]] while the user has an unfinished move (versolauth/versola#1051). Reads are not blocked. */
  def patch(request: PatchUserRequest): IO[UserNotFound | UserConflict | UserMoving | Throwable, Unit]

  def patchClaims(id: UserId, patch: Json.Obj): IO[UserMoving | Throwable, Unit]

  /** Hard-deletes the user: everything auth holds for them first -- sessions, refresh tokens,
    * passkeys, passwords, roles, throttling, pending conversations -- then the index row. Fails
    * with [[UserNotFound]] when the id is not indexed, and leaves the index row in place when
    * auth's purge fails, so the deletion can be retried rather than leaving a user nothing can
    * find but who can still sign in. */
  def delete(id: UserId): IO[UserNotFound | UserMoving | Throwable, Unit]

  def updateRoles(request: UpdateUserRolesRequest): IO[UserMoving | Throwable, Unit]

  def resetLimits(request: ResetUserLimitsRequest): Task[Unit]

  def listPasskeys(userId: UserId): Task[List[PasskeyInfo]]

  def renamePasskey(request: RenamePasskeyRequest): IO[UserMoving | Throwable, Unit]

  def deletePasskey(userId: UserId, credentialId: String): IO[UserMoving | Throwable, Unit]

  def resetPassword(request: ResetPasswordRequest): IO[UserMoving | Throwable, Option[String]]

  def setPassword(userId: UserId, password: String): IO[UserMoving | Throwable, Unit]

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

  /** The cell a login nobody holds is sent to: chosen by the MAC of the login, which is keyed, so that it cannot be
    * computed outside central and is the same every time while the cells are the same. */
  private[users] def decoyCell(cells: Vector[CellId], mac: IdentifierMac): Option[CellId] =
    Option.when(cells.nonEmpty):
      val sorted = cells.sortBy(identity[String])
      sorted((BigInt(1, mac) % sorted.size).toInt)

  /** The cell an email or a phone number nobody holds is sent to (versolauth/versola#1052): weighted rendezvous hashing
    * over the cells that take users (open, weight above 0 -- the filter of [[CellPlacement.pick]]), keyed by the MAC of
    * the address.
    *
    * Why it exists: a cell that does not hold the address sends the browser on when another cell holds it, and
    * registers or answers "unknown" when nobody does. If nobody's address were sent on, the redirect would tell which
    * addresses are registered. Sending an unheld address to a cell as well makes the two answers the same to an observer.
    * The MAC is keyed with a secret only central has, so an outside observer cannot compute which cell an address maps
    * to, and cannot tell a held address from an unheld one by where it is sent. What it can see is that the same address
    * always goes to the same cell while the cells and weights are the same; that is a property of the address, not of
    * whether anyone holds it.
    *
    * Why rendezvous: for each taking cell, `u` is a number strictly inside (0, 1) from the first 8 bytes of
    * `SHA-256(mac ++ 0x00 ++ cellId utf8)` and `score = -weight / ln(u)`; the highest score wins. A cell is chosen with
    * probability proportional to its weight, and a change of one cell's weight moves addresses only to or from that
    * cell; removing a cell moves only that cell's addresses.
    *
    * `u` is built from the top 52 bits of those 8 bytes as `(bits + 0.5) / 2^52`, which is exactly representable and
    * lies in `[2^-53, 1 - 2^-53]`: an all-zero or all-one digest gives neither `ln(0)` nor `ln(1) = 0` (a division by
    * zero). Ties (equal scores) go to the smaller cell id, so the choice never depends on the order of `cells`.
    *
    * @return `None` when no cell takes users
    */
  private[users] def placementCell(cells: Vector[CellRecord], mac: IdentifierMac): Option[CellId] =
    cells
      .filter(cell => cell.state == CellState.Open && cell.weight > 0)
      .map(cell => cell.id -> rendezvousScore(cell.weight, rendezvousUnit(mac, cell.id)))
      .sortBy(_._1: String)
      .reduceLeftOption((best, next) => if next._2 > best._2 then next else best)
      .map(_._1)

  private[users] def rendezvousUnit(mac: IdentifierMac, cellId: CellId): Double =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(mac.toArray)
    digest.update(0.toByte)
    digest.update((cellId: String).getBytes(StandardCharsets.UTF_8))
    unitOf(digest.digest())

  /** A number strictly inside (0, 1) from the first 8 bytes of a digest: the top 52 bits, `(bits + 0.5) / 2^52`. */
  private[users] def unitOf(digest: Array[Byte]): Double =
    val bits = ByteBuffer.wrap(digest, 0, 8).getLong >>> 12
    (bits.toDouble + 0.5) / 4503599627370496.0

  /** `-weight / ln(u)`: finite and positive for a positive weight and `u` in (0, 1), larger for a larger weight. */
  private[users] def rendezvousScore(weight: Int, u: Double): Double = -weight.toDouble / math.log(u)

  val live: ZLayer[
    UserRepository & AuthClient & OAuthClientService & SecureRandom & DirectoryKey & TopologyRepository & UserMoveRepository,
    Nothing,
    UserService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  class Impl(
      userRepository: UserRepository,
      authClient: AuthClient,
      oAuthClientService: OAuthClientService,
      secureRandom: SecureRandom,
      directoryKey: DirectoryKey,
      topology: TopologyRepository,
      moves: UserMoveRepository,
  ) extends UserService:
    /** Fails with [[UserMoving]] when the user has a move that is not finished. */
    private def idle(id: UserId): IO[UserMoving | Throwable, Unit] =
      moves.unfinished(id).flatMap(moving => ZIO.fail(UserMoving).when(moving)).unit

    /** A change made in the user's cell: refused as [[UserMoving]] also when the move began after [[idle]] looked, which the
      * cell itself says. */
    private def whileIdle[A](id: UserId)(change: Task[A]): IO[UserMoving | Throwable, A] =
      idle(id) *> change.catchSome { case AuthClient.UserLeavingCell => ZIO.fail(UserMoving) }

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
          // A cell does not answer for a user that is leaving it: the record is shown with its move and without what the cell holds.
          held <- authClient.getUser(r.id).catchSome { case AuthClient.UserLeavingCell => ZIO.none }
          pending <- userRepository.pendingEvents(r.id)
          move <- moves.find(r.id)
        yield
          val user = pending.foldLeft(held)(UserService.applied)
          UserSearchRecord(
            id = r.id,
            email = user.flatMap(_.email),
            phone = user.flatMap(_.phone),
            login = user.flatMap(_.login),
            claims = user.fold(Json.Obj())(_.claims),
            cell = r.cellId,
            move = move.map(UserMoveResponse.of),
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

    override def supersedeSession(request: SupersededSessionRequest): Task[Boolean] =
      authClient.supersedeSession(request.userId, request.sessionId)

    override def revokeRefreshTokens(userId: UserId, clientId: ClientId): IO[UserMoving | Throwable, Unit] =
      whileIdle(userId)(authClient.revokeRefreshTokens(userId, clientId))

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

    override def homeCell(request: HomeCellRequest): Task[Option[CellId]] =
      def ofAddress(identifier: DirectoryIdentifier): Task[Option[CellId]] =
        userRepository.findByIdentifier(identifier).flatMap:
          case Some(owner) => ZIO.succeed(owner.cellId)
          case None => topology.getCells.map(cells => UserService.placementCell(cells, identifier.mac))
      (request.login, request.userId, request.email, request.phone) match
        case (Some(login), None, None, None) =>
          val identifier = directoryKey.login(login)
          userRepository.findByIdentifier(identifier).flatMap:
            case Some(owner) => ZIO.succeed(owner.cellId)
            case None => topology.getCells.map(cells => UserService.decoyCell(cells.map(_.id), identifier.mac))
        case (None, Some(userId), None, None) => userRepository.find(userId).map(_.flatMap(_.cellId))
        case (None, None, Some(email), None) => ofAddress(directoryKey.email(email))
        case (None, None, None, Some(phone)) => ofAddress(directoryKey.phone(phone))
        case _ => ZIO.none

    override def patch(request: PatchUserRequest): IO[UserNotFound | UserConflict | UserMoving | Throwable, Unit] =
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
        _ <- idle(request.id)
        version <- secureRandom.nextUUIDv7
        _ <- userRepository.replaceIdentifiers(
          id = request.id,
          set = changes.flatMap(_._2),
          remove = changes.collect { case (kind, None) => kind }.toSet,
          event = OutboxEvent.PatchUser(userId = request.id, version = version, email = request.email, phone = request.phone, login = request.login),
          eventId = version,
        )
      yield ()

    override def patchClaims(id: UserId, patch: Json.Obj): IO[UserMoving | Throwable, Unit] =
      whileIdle(id)(authClient.patchUserClaims(id, patch))

    override def delete(id: UserId): IO[UserNotFound | UserMoving | Throwable, Unit] =
      for
        _ <- userRepository.find(id).someOrFail(UserNotFound)
        _ <- whileIdle(id)(authClient.deleteUser(id))
        // The index row goes with a `DeleteUser` event queued behind it: the outbox delivers
        // per user in order, so an upsert still in flight for this user is followed by a
        // second, idempotent purge rather than resurrecting the account in auth.
        _ <- userRepository.delete(id)
      yield ()

    override def updateRoles(request: UpdateUserRolesRequest): IO[UserMoving | Throwable, Unit] =
      idle(request.userId) *> userRepository.enqueueRoleUpdate(request.userId, request.tenantId, request.add, request.remove)

    override def resetLimits(request: ResetUserLimitsRequest): Task[Unit] =
      authClient.resetUserLimits(request.userId, request.tenantId, request.email, request.phone)

    override def listPasskeys(userId: UserId): Task[List[PasskeyInfo]] =
      authClient.listPasskeys(userId)

    override def renamePasskey(request: RenamePasskeyRequest): IO[UserMoving | Throwable, Unit] =
      whileIdle(request.userId)(authClient.renamePasskey(request.userId, request.credentialId, request.name))

    override def deletePasskey(userId: UserId, credentialId: String): IO[UserMoving | Throwable, Unit] =
      whileIdle(userId)(authClient.deletePasskey(userId, credentialId))

    override def resetPassword(request: ResetPasswordRequest): IO[UserMoving | Throwable, Option[String]] =
      whileIdle(request.userId)(authClient.resetPassword(request.userId, request.expiresInSeconds, request.channel))

    override def setPassword(userId: UserId, password: String): IO[UserMoving | Throwable, Unit] =
      whileIdle(userId)(authClient.setPassword(userId, password))
