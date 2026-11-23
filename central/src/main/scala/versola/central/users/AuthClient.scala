package versola.central.users

import versola.central.CentralConfig
import versola.central.antifraud.*
import versola.central.audit.{AuthAuditError, CentralAudit, UserAuditEventsQuery, UserAuditEventsResponse}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.TopologyRepository
import versola.util.audit.AuditContext
import versola.util.http.Observability
import versola.util.{Email, Patch, Phone}
import zio.Duration
import zio.http.{Body, Client, Header, MediaType, Method, QueryParams, Request, Response, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Chunk, IO, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

import java.io.IOException
import java.time.Instant
import java.util.UUID

trait AuthClient:
  def upsertUser(
      id: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ): Task[Unit]

  /** Changes the identifiers named and leaves the others as `auth` has them. */
  def patchUser(
      id: UserId,
      version: UUID,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
  ): Task[Unit]

  def updateUserRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]

  def deleteUser(userId: UserId): Task[Unit]

  /** The user as `auth` holds it: its identifiers, which central keeps only the MACs of, and its claims. */
  def getUser(id: UserId): Task[Option[AuthClient.UserDto]]

  def getUserClaims(id: UserId): Task[Option[Json.Obj]]

  def patchUserClaims(id: UserId, patch: Json.Obj): Task[Unit]

  def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

  def getUserSessions(id: UserId): Task[List[AuthClient.SessionDto]]

  /** Every remembered user agent of the user with its live session, if any. */
  def getUserDevices(id: UserId): Task[List[AuthClient.DeviceDto]]

  /** Forgets a user agent with its session and their refresh tokens. `false` when auth does not
    * know it as this user's. */
  def forgetUserAgent(userId: UserId, userAgentId: String): Task[Boolean]

  def invalidateSession(userId: UserId): Task[Unit]

  /** Ends one session of the user. `false` when auth does not know it as this user's. */
  def endSession(userId: UserId, sessionId: String): Task[Boolean]

  /** Ends one session of the user because a sign-in that finished in another cell took its place: ended as
    * [[endSession]] ends it, and recorded as superseded, not as an administrator's action. `false` when auth does not
    * know it as this user's. */
  def supersedeSession(userId: UserId, sessionId: String): Task[Boolean]

  /** Revokes the offline access `clientId` holds for the user: its live refresh tokens. */
  def revokeRefreshTokens(userId: UserId, clientId: ClientId): Task[Unit]

  /** Every currently-live refresh token issued to the user. Not derivable from
    * [[getUserSessions]]: a refresh token's expiry is fixed at issuance and independent of a
    * session's, so a token routinely outlives the session it was issued under. */
  def getUserRefreshTokens(id: UserId): Task[List[AuthClient.RefreshTokenDto]]
  def resetUserLimits(
      userId: UserId,
      tenantId: TenantId,
      email: Option[Email],
      phone: Option[Phone],
  ): Task[Unit]

  def listPasskeys(userId: UserId): Task[List[PasskeyInfo]]

  def renamePasskey(userId: UserId, credentialId: String, name: Option[String]): Task[Unit]

  def deletePasskey(userId: UserId, credentialId: String): Task[Unit]

  /** Returns the plaintext temporary password when the reset asked for it to be
    * shown rather than delivered (non-prod only); `None` otherwise.
    */
  def resetPassword(
      userId: UserId,
      expiresInSeconds: Long,
      channel: Option[DeliveryChannel],
  ): Task[Option[String]]

  def setPassword(userId: UserId, password: String): Task[Unit]

  /** Forces auth to reload all configuration caches from central (non-prod only). */
  def syncConfiguration(): Task[Unit]

  /** A page of the user's audit events from auth, newest first (`GET /users/audit-events?id=`). */
  def getUserAuditEvents(userId: UserId, query: UserAuditEventsQuery): IO[AuthAuditError | Throwable, UserAuditEventsResponse]

  /** Auth's summary of the user's audit events over the window (`GET /users/audit-summary?id=`), as it answers it: central
    * adds to it and does not read into it. */
  def getUserAuditSummary(userId: UserId, from: Option[Instant], to: Option[Instant]): IO[AuthAuditError | Throwable, Json.Obj]

  /** Central stores the antifraud lists and settings, and auth says whether an entry or the settings are valid before
    * they are stored: the rules of what is valid belong to the engine that enforces them, and auth has the key a phone is
    * hashed with. Its refusals are [[AntifraudListError]]s, not failures of the call.
    *
    * One `auth` answers a check, and every cell a preview, a count of hits and a reset: what an entry matched, how often
    * it fired and the counters are each cell's own. */
  def checkAntifraudListEntry(entry: ListEntryCheck): IO[AntifraudListError | Throwable, CheckedListEntry]

  /** The check, with what the entry would have matched of the last 24 hours of events, summed over every cell. One cell
    * that cannot answer fails the call: a sum without it would understate what the entry does. */
  def previewAntifraudListEntry(entry: ListEntryCheck): IO[AntifraudListError | Throwable, CheckedListEntry]

  /** How often each of `ids` matched, summed over the cells. A cell that cannot answer is left out and logged: the
    * counters are for reading, and a listing must not fail on one cell. */
  def antifraudListHits(ids: List[UUID]): Task[List[ListHits]]

  /** The settings a change would make, validated: `check.patch` applied to `check.settings`. */
  def checkAntifraudSettings(check: SettingsCheck): IO[AntifraudListError | Throwable, AntifraudSettings]

  /** Clears counters in every cell; each answers 202 with the generation of its reset, and the highest is answered. */
  def resetAntifraudCounters(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse]

  /** The catalogue of rule names, and what one auth instance says of its antifraud. */
  def antifraudRules: IO[AntifraudListError | Throwable, AntifraudRulesResponse]

  def antifraudStatus: IO[AntifraudListError | Throwable, AntifraudInstanceStatus]

  /** What an instance of each registered cell says of its antifraud; a cell that does not answer is listed with why. None
    * where there is no cell. */
  def antifraudStatusOfCells: Task[List[AntifraudCellStatus]]

object AuthClient:
  /** The `auth` central is configured with, and no other. */
  val live: ZLayer[Scope & Client & CentralConfig, Throwable, AuthClient] =
    AuthTokenService.live >>> ZLayer.fromFunction((client: Client, config: CentralConfig, tokens: AuthTokenService) =>
      Impl(client, config.auth.url, tokens),
    )

  /** Every call about a user goes to the cell that holds the user ([[CellAuthClient]]). */
  val routed: ZLayer[Scope & Client & CentralConfig & UserRepository & TopologyRepository, Throwable, AuthClient] =
    AuthTokenService.live >>> ZLayer.fromFunction:
      (client: Client, config: CentralConfig, tokens: AuthTokenService, users: UserRepository, topology: TopologyRepository) =>
        CellAuthClient(
          configured = Impl(client, config.auth.url, tokens),
          at = url => Impl(client, url, tokens),
          users = users,
          topology = topology,
        )

  /** The name of the query parameter, and of the optional field of a body, that names the administrator on whose behalf central
    * calls auth: auth records it as the actor of what the call changes (an absent one is central itself). It is taken from the
    * audit context of the request, which the audit hook fills from what edge added to the request, so no controller has to pass it
    * on. The antifraud calls do not carry it: the administrator travels in their `userId`. */
  val ActorParam = "actorId"

  /** The query parameter that tells auth the address the administrator's call came from, when central knows it (`AuditHook`), so that
    * the row auth writes for the call has it too. On every call, whatever its method: an address is not a field of a payload. */
  val ActorIpParam = "actorIp"

  /** Auth's answer to an audit read that it had no place for (`503`, `Retry-After`): not a failure of the report, asked again. */
  private case class AuditBusy(after: Duration) extends Exception("auth is busy with other audit reads")

  private[users] case object TokenExpiredError extends Exception("central→auth token expired; retrying with fresh token")

  private case class UpsertUserPayload(
      id: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ) derives JsonCodec

  private case class UpdateUserRolesPayload(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ) derives JsonCodec

  private case class PatchUserPayload(
      id: UserId,
      version: UUID,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
  ) derives JsonCodec

  case class UserDto(email: Option[Email], phone: Option[Phone], login: Option[Login], claims: Json.Obj) derives JsonCodec

  private case class PatchUserClaimsPayload(id: UserId, claims: Json.Obj, actorId: Option[UserId]) derives JsonCodec

  private case class UserRolesResponse(roles: List[RoleId]) derives JsonCodec

  case class ClientEntryDto(
      clientId: ClientId,
      enteredAt: Instant,
  ) derives JsonCodec

  case class SessionDto(
      /** Not rendered to end users, but kept available for internal use (e.g. a future
       *  per-session invalidation call). */
      publicId: String,
      clients: List[ClientEntryDto],
      platform: Option[String],
      os: Option[String],
      browser: Option[String],
      version: Option[String],
      createdAt: String,
      expiresAt: Instant,
      idleExpiresAt: Option[Instant],
      userAgent: Option[UserAgentDto],
  ) derives JsonCodec

  case class UserAgentDto(
      id: String,
      userAgent: Option[String],
      platform: Option[String],
      os: Option[String],
      browser: Option[String],
      version: Option[String],
      createdAt: Instant,
      expiresAt: Instant,
  ) derives JsonCodec

  case class DeviceDto(
      userAgent: Option[UserAgentDto],
      session: Option[SessionDto],
      endedSessionIds: List[String],
  ) derives JsonCodec

  case class DeviceListResponse(devices: List[DeviceDto]) derives JsonCodec

  case class SessionListResponse(sessions: List[SessionDto]) derives JsonCodec

  case class RefreshTokenDto(
      sessionId: String,
      clientId: ClientId,
      scope: List[String],
      issuedAt: Instant,
      expiresAt: Instant,
      dpopBound: Boolean,
  ) derives JsonCodec

  case class RefreshTokenListResponse(refreshTokens: List[RefreshTokenDto]) derives JsonCodec

  private case class ResetUserLimitsPayload(
      userId: UserId,
      tenantId: TenantId,
      email: Option[Email],
      phone: Option[Phone],
      actorId: Option[UserId],
  ) derives JsonCodec

  private case class RenamePasskeyPayload(
      userId: UserId,
      credentialId: String,
      name: Option[String],
      actorId: Option[UserId],
  ) derives JsonCodec

  private case class ResetPasswordPayload(
      userId: UserId,
      expiresInSeconds: Long,
      channel: Option[DeliveryChannel],
      actorId: Option[UserId],
  ) derives JsonCodec

  private case class HitsPayload(ids: List[UUID]) derives JsonCodec

  private case class SetPasswordPayload(
      userId: UserId,
      password: String,
      actorId: Option[UserId],
  ) derives JsonCodec

  /** @param base the `auth` the calls go to: the one central is configured with, or a cell's own address. */
  class Impl(
      httpClient: Client,
      base: URL,
      tokenService: AuthTokenService,
  ) extends AuthClient:
    private val usersUrl: URL = base / "users"
    private val rolesUrl: URL = usersUrl / "roles"
    private val claimsUrl: URL = usersUrl / "claims"
    private val sessionsUrl: URL = usersUrl / "sessions"
    private val userAgentsUrl: URL = usersUrl / "user-agents"
    private val refreshTokensUrl: URL = usersUrl / "refresh-tokens"
    private val limitsResetUrl: URL = usersUrl / "limits" / "reset"
    private val passkeysUrl: URL = usersUrl / "passkeys"
    private val passwordResetUrl: URL = usersUrl / "password" / "reset"
    private val passwordSetUrl: URL = usersUrl / "password" / "set"
    private val antifraudListsUrl: URL = base / "antifraud" / "lists"
    private val antifraudSettingsUrl: URL = base / "antifraud" / "settings"
    private val antifraudCountersResetUrl: URL = base / "antifraud" / "counters" / "reset"
    private val antifraudRulesUrl: URL = base / "antifraud" / "rules"
    private val antifraudStatusUrl: URL = base / "antifraud" / "status"
    private val configSyncUrl: URL = base / "service" / "configuration" / "sync"
    private val auditEventsUrl: URL = usersUrl / "audit-events"
    private val auditSummaryUrl: URL = usersUrl / "audit-summary"

    override def upsertUser(
        id: UserId,
        version: UUID,
        email: Option[Email],
        phone: Option[Phone],
        login: Option[Login],
    ): Task[Unit] =
      send(
        Request(
          method = Method.PUT,
          url = usersUrl,
          body = Body.from(UpsertUserPayload(id, version, email, phone, login)),
        ),
      )

    override def patchUser(
        id: UserId,
        version: UUID,
        email: Option[Patch[Email]],
        phone: Option[Patch[Phone]],
        login: Option[Patch[Login]],
    ): Task[Unit] =
      send(
        Request(
          method = Method.PATCH,
          url = usersUrl,
          body = Body.from(PatchUserPayload(id = id, version = version, email = email, phone = phone, login = login)),
        ),
      )

    override def updateUserRoles(
        userId: UserId,
        tenantId: TenantId,
        add: Set[RoleId],
        remove: Set[RoleId],
    ): Task[Unit] =
      send(
        Request(
          method = Method.PATCH,
          url = rolesUrl,
          body = Body.from(UpdateUserRolesPayload(userId, tenantId, add, remove)),
        ),
      )

    override def getUser(id: UserId): Task[Option[UserDto]] =
      sendReceiveOptional[UserDto](Request.get(claimsUrl.addQueryParam("id", id.toString)))

    override def getUserClaims(id: UserId): Task[Option[Json.Obj]] =
      getUser(id).map(_.map(_.claims))

    override def patchUserClaims(id: UserId, patch: Json.Obj): Task[Unit] =
      admin.flatMap: actorId =>
        send(
          Request(
            method = Method.PATCH,
            url = claimsUrl,
            body = Body.from(PatchUserClaimsPayload(id, patch, actorId)),
          ),
        )

    override def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]] =
      sendReceive[UserRolesResponse](
        Request.get(rolesUrl.addQueryParam("id", id.toString).addQueryParam("tenantId", tenantId)),
      ).map(_.roles)

    override def getUserSessions(id: UserId): Task[List[SessionDto]] =
      sendReceive[SessionListResponse](
        Request.get(sessionsUrl.addQueryParam("id", id.toString)),
      ).map(_.sessions)

    override def getUserDevices(id: UserId): Task[List[DeviceDto]] =
      sendReceive[DeviceListResponse](
        Request.get(userAgentsUrl.addQueryParam("id", id.toString)),
      ).map(_.devices)

    override def forgetUserAgent(userId: UserId, userAgentId: String): Task[Boolean] =
      execute(
        Request(
          method = Method.DELETE,
          url = userAgentsUrl.addQueryParam("userId", userId.toString).addQueryParam("userAgentId", userAgentId),
          body = Body.empty,
        ),
      ): response =>
        response.status match
          case Status.NotFound => ZIO.succeed(false)
          case status if status.isSuccess => ZIO.succeed(true)
          case _ => failResponse(response)

    override def invalidateSession(userId: UserId): Task[Unit] =
      send(
        Request(
          method = Method.DELETE,
          url = sessionsUrl.addQueryParam("userId", userId.toString),
          body = Body.empty,
        ),
      )

    override def endSession(userId: UserId, sessionId: String): Task[Boolean] =
      endOne(sessionsUrl.addQueryParam("userId", userId.toString).addQueryParam("sessionId", sessionId))

    override def supersedeSession(userId: UserId, sessionId: String): Task[Boolean] =
      endOne(sessionsUrl.addQueryParam("userId", userId.toString).addQueryParam("sessionId", sessionId).addQueryParam("cause", "superseded"))

    private def endOne(url: URL): Task[Boolean] =
      execute(Request(method = Method.DELETE, url = url, body = Body.empty)): response =>
        response.status match
          case Status.NotFound => ZIO.succeed(false)
          case status if status.isSuccess => ZIO.succeed(true)
          case _ => failResponse(response)

    override def revokeRefreshTokens(userId: UserId, clientId: ClientId): Task[Unit] =
      send(
        Request(
          method = Method.DELETE,
          url = refreshTokensUrl.addQueryParam("userId", userId.toString).addQueryParam("clientId", clientId),
          body = Body.empty,
        ),
      )

    override def getUserRefreshTokens(id: UserId): Task[List[RefreshTokenDto]] =
      sendReceive[RefreshTokenListResponse](
        Request.get(refreshTokensUrl.addQueryParam("id", id.toString)),
      ).map(_.refreshTokens)
    override def resetUserLimits(
        userId: UserId,
        tenantId: TenantId,
        email: Option[Email],
        phone: Option[Phone],
    ): Task[Unit] =
      admin.flatMap: actorId =>
        send(
          Request(
            method = Method.POST,
            url = limitsResetUrl,
            body = Body.from(ResetUserLimitsPayload(userId, tenantId, email, phone, actorId)),
          ),
        )

    override def listPasskeys(userId: UserId): Task[List[PasskeyInfo]] =
      sendReceive[ListPasskeysResponse](
        Request.get(passkeysUrl.addQueryParam("id", userId.toString)),
      ).map(_.passkeys)

    override def renamePasskey(userId: UserId, credentialId: String, name: Option[String]): Task[Unit] =
      admin.flatMap: actorId =>
        send(
          Request(
            method = Method.PATCH,
            url = passkeysUrl,
            body = Body.from(RenamePasskeyPayload(userId, credentialId, name, actorId)),
          ),
        )

    override def deletePasskey(userId: UserId, credentialId: String): Task[Unit] =
      send(
        Request(
          method = Method.DELETE,
          url = passkeysUrl
            .addQueryParam("id", userId.toString)
            .addQueryParam("credentialId", credentialId),
        ),
      )

    override def resetPassword(
        userId: UserId,
        expiresInSeconds: Long,
        channel: Option[DeliveryChannel],
    ): Task[Option[String]] =
      admin.flatMap: actorId =>
        sendReceiveOptional[ResetPasswordResponse](
          Request(
            method = Method.POST,
            url = passwordResetUrl,
            body = Body.from(ResetPasswordPayload(userId, expiresInSeconds, channel, actorId)),
          ),
        ).map(_.map(_.password))

    override def setPassword(userId: UserId, password: String): Task[Unit] =
      admin.flatMap: actorId =>
        send(
          Request(
            method = Method.POST,
            url = passwordSetUrl,
            body = Body.from(SetPasswordPayload(userId, password, actorId)),
          ),
        )

    override def checkAntifraudListEntry(entry: ListEntryCheck): IO[AntifraudListError | Throwable, CheckedListEntry] =
      antifraud[CheckedListEntry](Request(method = Method.POST, url = antifraudListsUrl / "check", body = Body.from(entry.copy(preview = false))))

    override def previewAntifraudListEntry(entry: ListEntryCheck): IO[AntifraudListError | Throwable, CheckedListEntry] =
      antifraud[CheckedListEntry](Request(method = Method.POST, url = antifraudListsUrl / "check", body = Body.from(entry.copy(preview = true))))

    override def antifraudListHits(ids: List[UUID]): Task[List[ListHits]] =
      if ids.isEmpty then ZIO.succeed(Nil)
      else sendReceive[List[ListHits]](Request(method = Method.POST, url = antifraudListsUrl / "hits", body = Body.from(AuthClient.HitsPayload(ids))))

    override def checkAntifraudSettings(check: SettingsCheck): IO[AntifraudListError | Throwable, AntifraudSettings] =
      antifraud[AntifraudSettings](Request(method = Method.POST, url = antifraudSettingsUrl / "check", body = Body.from(check)))

    override def resetAntifraudCounters(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse] =
      antifraud[ResetCountersResponse](Request(method = Method.POST, url = antifraudCountersResetUrl, body = Body.from(request)))

    override def antifraudRules: IO[AntifraudListError | Throwable, AntifraudRulesResponse] =
      antifraud[AntifraudRulesResponse](Request.get(antifraudRulesUrl))

    override def antifraudStatus: IO[AntifraudListError | Throwable, AntifraudInstanceStatus] =
      antifraud[AntifraudInstanceStatus](Request.get(antifraudStatusUrl))

    // One `auth` knows of no cell.
    override def antifraudStatusOfCells: Task[List[AntifraudCellStatus]] = ZIO.succeed(Nil)

    private def antifraud[A: JsonDecoder](request: Request): IO[AntifraudListError | Throwable, A] =
      antifraudAnswer(request): response =>
        response.body.asString.flatMap: body =>
          ZIO.fromEither(body.fromJson[A]).mapError(msg => new RuntimeException(s"Auth response decode failed: $msg"))

    /** Auth's 400 is its answer to an entry or settings it will not take, not a failure of the call. */
    private def antifraudAnswer[A](request: Request)(success: Response => Task[A]): IO[AntifraudListError | Throwable, A] =
      execute(request): response =>
        response.status match
          case status if status.isSuccess => success(response).map(Right(_))
          case Status.BadRequest => response.body.asString.map(message => Left(AntifraudListError.Invalid(message)))
          case _ => failResponse(response)
      .flatMap(ZIO.fromEither(_))

    override def deleteUser(userId: UserId): Task[Unit] =
      send(
        Request(
          method = Method.DELETE,
          url = usersUrl.addQueryParam("id", userId.toString),
        ),
      )

    override def syncConfiguration(): Task[Unit] =
      send(Request(method = Method.POST, url = configSyncUrl, body = Body.empty))

    override def getUserAuditEvents(userId: UserId, query: UserAuditEventsQuery): IO[AuthAuditError | Throwable, UserAuditEventsResponse] =
      val params = List(
        query.from.map("from" -> _.toString),
        query.to.map("to" -> _.toString),
        query.cursor.map("cursor" -> _),
        Some("limit" -> query.limit.toString),
      ).flatten
      auditAnswer[UserAuditEventsResponse](Request.get(auditEventsUrl.addQueryParams(("id" -> userId.toString) :: params)))

    override def getUserAuditSummary(userId: UserId, from: Option[Instant], to: Option[Instant]): IO[AuthAuditError | Throwable, Json.Obj] =
      val params = List(from.map("from" -> _.toString), to.map("to" -> _.toString)).flatten
      auditAnswer[Json.Obj](Request.get(auditSummaryUrl.addQueryParams(("id" -> userId.toString) :: params)))

    /** Auth's 400 is its refusal of the window or the cursor; a 5xx, a 404 (an auth without the endpoint) and a connection that
      * cannot be made are auth not answering. */
    private def auditAnswer[A: JsonDecoder](request: Request): IO[AuthAuditError | Throwable, A] =
      attemptAudit[A](request)
        // Auth says it is busy with other reads (503 and how long to wait): asked once more after that, and then it is not answering.
        .catchSome { case AuthClient.AuditBusy(after) => ZIO.sleep(after) *> attemptAudit[A](request) }
        .catchSome { case _: AuthClient.AuditBusy => ZIO.succeed(Left(AuthAuditError.Unavailable("auth answered 503"))) }
        .catchSome:
          case _: IOException => ZIO.succeed(Left(AuthAuditError.Unavailable("auth could not be reached")))
        .flatMap(ZIO.fromEither(_))

    private def attemptAudit[A: JsonDecoder](request: Request): Task[Either[AuthAuditError, A]] =
      execute(request): response =>
        response.status match
          case status if status.isSuccess =>
            response.body.asString.flatMap: body =>
              ZIO.fromEither(body.fromJson[A]).mapBoth(msg => new RuntimeException(s"Auth response decode failed: $msg"), Right(_))
          case Status.BadRequest => response.body.asString.map(message => Left(AuthAuditError.Invalid(message)))
          case Status.ServiceUnavailable =>
            val seconds = response.headers.get("Retry-After").flatMap(_.trim.toLongOption).getOrElse(1L)
            ZIO.fail(AuthClient.AuditBusy(Duration.fromSeconds(seconds.max(1L).min(2L))))
          case Status.NotFound => ZIO.succeed(Left(AuthAuditError.EndpointMissing))
          case status if status.isServerError =>
            ZIO.succeed(Left(AuthAuditError.Unavailable(s"auth answered ${status.code}")))
          case status => ZIO.fail(new RuntimeException(s"Auth call failed: ${status.code}"))

    /** The administrator the request being served came from: a call central makes on its own (the outbox) or for a provisioning
      * client names nobody, and auth records central. */
    private val admin: ZIO[Any, Nothing, Option[UserId]] =
      AuditContext.current.map(
        _.actor.collect { case (actorType, Some(id)) if actorType == CentralAudit.Actors.Admin => id }.flatMap(UserId.parse(_).toOption),
      )

    /** A `GET` or a `DELETE` to the user or the audit endpoints of auth. Their calls with a body have the field in it. */
    private def carriesActorInQuery(request: Request): Boolean =
      (request.method == Method.GET || request.method == Method.DELETE) &&
        request.url.path.startsWith(usersUrl.path)

    private def execute[A](request: Request)(handle: Response => Task[A]): Task[A] =
      def attemptWithToken(request: Request)(token: String): Task[A] =
        withConnectionRetry:
          ZIO.scoped:
            httpClient.request(authorized(request, token)).flatMap: response =>
              if response.status == Status.Unauthorized then ZIO.fail(AuthClient.TokenExpiredError)
              else handle(response)
      for
        actor <- admin
        ip <- Observability.currentIp
        // On behalf of the administrator the request came from, when it is one: a call that has no body carries it in its query.
        named =
          actor.filter(_ => carriesActorInQuery(request)).fold(request)(id => request.updateURL(_.addQueryParam(AuthClient.ActorParam, id.toString)))
        onBehalf = ip.fold(named)(address => named.updateURL(_.addQueryParam(AuthClient.ActorIpParam, address)))
        token <- tokenService.getToken
        result <- attemptWithToken(onBehalf)(token).catchSome:
          case AuthClient.TokenExpiredError =>
            tokenService.refreshToken().flatMap(attemptWithToken(onBehalf))
      yield result

    private def authorized(request: Request, token: String): Request =
      request
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader(Header.Authorization.Bearer(token))

    private def send(request: Request): Task[Unit] =
      execute(request): response =>
        if response.status.isSuccess then ZIO.unit
        else failResponse(response)

    private def sendReceive[A: JsonDecoder](request: Request): Task[A] =
      execute(request): response =>
        if response.status.isSuccess then
          response.body.asString.flatMap: body =>
            ZIO.fromEither(body.fromJson[A])
              .mapError(msg => new RuntimeException(s"Auth response decode failed: $msg"))
        else failResponse(response)

    private def sendReceiveOptional[A: JsonDecoder](request: Request): Task[Option[A]] =
      execute(request): response =>
        response.status match
          case Status.NoContent => ZIO.none
          case s if s.isSuccess =>
            response.body.asString.flatMap: body =>
              ZIO.fromEither(body.fromJson[A])
                .mapBoth(
                  msg => new RuntimeException(s"Auth response decode failed: $msg"),
                  Some(_),
                )
          case _ =>
            failResponse(response)

    private def failResponse(response: Response): Task[Nothing] =
      response.body.asString.flatMap: body =>
        ZIO.logError(s"Auth call failed: ${response.status.code} $body") *>
          ZIO.fail(new RuntimeException(s"Auth call failed: ${response.status.code} $body"))

    /** Retries on transient connection failures (stale pooled channel after auth restart). */
    private def withConnectionRetry[A](effect: Task[A]): Task[A] =
      effect.retry(
        Schedule.recurWhile[Throwable] {
          case _: java.net.ConnectException => true
          case _: java.io.IOException => true
          case _ => false
        } && Schedule.spaced(200.millis) && Schedule.recurs(3),
      )
