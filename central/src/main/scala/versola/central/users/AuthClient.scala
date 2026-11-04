package versola.central.users

import versola.central.CentralConfig
import versola.central.antifraud.*
import versola.central.audit.{AuthAuditError, CentralAudit, UserAuditEventsQuery, UserAuditEventsResponse}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.audit.AuditContext
import versola.util.http.EntityVersion
import versola.util.{Email, PageLimit, Patch, Phone}
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

  def updateUserRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]

  def deleteUser(userId: UserId): Task[Unit]

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

  /** A page of the user's audit events from auth, newest first (`GET /audit/events?userId=`). */
  def getUserAuditEvents(userId: UserId, query: UserAuditEventsQuery): IO[AuthAuditError | Throwable, UserAuditEventsResponse]

  /** Auth's summary of the user's audit events over the window (`GET /audit/summary?userId=`), as it answers it: central
    * adds to it and does not read into it. */
  def getUserAuditSummary(userId: UserId, from: Option[Instant], to: Option[Instant]): IO[AuthAuditError | Throwable, Json.Obj]

  /** The antifraud lists live in auth, which validates the entries: its refusals are [[AntifraudListError]]s, not
    * failures of the call. `userId` is the admin who asked, recorded by auth as who made the change. `caller` is whose scope
    * the call is made in; auth enforces it, and it is sent as the call's own fields (`scope` and `callerTenantId`), never taken
    * from what the request says. */
  def createAntifraudListEntry(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntryResponse]

  def previewAntifraudListEntry(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, PreviewResponse]

  def listAntifraudListEntries(query: ListEntriesQuery, caller: AntifraudCaller): IO[AntifraudListError | Throwable, List[ListEntryResponse]]

  def deleteAntifraudListEntry(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, Unit]

  def antifraudListAudit(
      entryId: Option[UUID],
      limit: Option[PageLimit],
      caller: AntifraudCaller,
  ): IO[AntifraudListError | Throwable, List[AuditRecordResponse]]

  /** The antifraud settings live in auth too, which validates them: its refusals are [[AntifraudListError]]s as well. A change
    * made on a version the settings have left is [[AntifraudListError.StaleVersion]]. */
  def getAntifraudSettings: IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** `request.version` is the version the change is made on, when the caller named one; without it the settings are replaced. */
  def putAntifraudSettings(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** A typed patch of the settings (see [[PatchAntifraudSettingsRequest]]); auth applies it, validates and answers all of them. */
  def patchAntifraudSettings(request: PatchAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** Puts everything off; on `expectedVersion` if the caller named one. */
  def deleteAntifraudSettings(request: DeleteAntifraudSettingsRequest, expectedVersion: Option[Long]): IO[AntifraudListError | Throwable, Unit]

  /** Clears counters; auth owns them and answers 202 with the generation of the reset. */
  def resetAntifraudCounters(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse]

  /** The catalogue of rule names, and what one auth instance says of its antifraud. */
  def antifraudRules: IO[AntifraudListError | Throwable, AntifraudRulesResponse]

  def antifraudStatus: IO[AntifraudListError | Throwable, AntifraudStatusResponse]

object AuthClient:
  val live: ZLayer[Scope & Client & CentralConfig, Throwable, AuthClient] =
    AuthTokenService.live >>> ZLayer.fromFunction(Impl(_, _, _))

  /** The name of the query parameter, and of the optional field of a body, that names the administrator on whose behalf central
    * calls auth: auth records it as the actor of what the call changes (an absent one is central itself). It is taken from the
    * audit context of the request, which the audit hook fills from what edge added to the request, so no controller has to pass it
    * on. The antifraud calls do not carry it: the administrator travels in their `userId`. */
  val ActorParam = "actorId"

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

  private case class UserClaimsResponse(claims: Json.Obj) derives JsonCodec

  private case class PatchUserClaimsPayload(id: UserId, claims: Json.Obj, actorId: Option[UserId] = None) derives JsonCodec

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
      actorId: Option[UserId] = None,
  ) derives JsonCodec

  private case class RenamePasskeyPayload(
      userId: UserId,
      credentialId: String,
      name: Option[String],
      actorId: Option[UserId] = None,
  ) derives JsonCodec

  private case class ResetPasswordPayload(
      userId: UserId,
      expiresInSeconds: Long,
      channel: Option[DeliveryChannel],
      actorId: Option[UserId] = None,
  ) derives JsonCodec

  private case class SetPasswordPayload(
      userId: UserId,
      password: String,
      actorId: Option[UserId] = None,
  ) derives JsonCodec

  class Impl(
      httpClient: Client,
      config: CentralConfig,
      tokenService: AuthTokenService,
  ) extends AuthClient:
    private val usersUrl: URL = config.auth.url / "users"
    private val rolesUrl: URL = usersUrl / "roles"
    private val claimsUrl: URL = usersUrl / "claims"
    private val sessionsUrl: URL = usersUrl / "sessions"
    private val userAgentsUrl: URL = usersUrl / "user-agents"
    private val refreshTokensUrl: URL = usersUrl / "refresh-tokens"
    private val limitsResetUrl: URL = usersUrl / "limits" / "reset"
    private val passkeysUrl: URL = usersUrl / "passkeys"
    private val passwordResetUrl: URL = usersUrl / "password" / "reset"
    private val passwordSetUrl: URL = usersUrl / "password" / "set"
    private val antifraudListsUrl: URL = config.auth.url / "antifraud" / "lists"
    private val antifraudSettingsUrl: URL = config.auth.url / "antifraud" / "settings"
    private val antifraudCountersResetUrl: URL = config.auth.url / "antifraud" / "counters" / "reset"
    private val antifraudRulesUrl: URL = config.auth.url / "antifraud" / "rules"
    private val antifraudStatusUrl: URL = config.auth.url / "antifraud" / "status"
    private val configSyncUrl: URL = config.auth.url / "service" / "configuration" / "sync"
    private val auditUrl: URL = config.auth.url / "audit"
    private val auditEventsUrl: URL = auditUrl / "events"
    private val auditSummaryUrl: URL = auditUrl / "summary"

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

    override def getUserClaims(id: UserId): Task[Option[Json.Obj]] =
      sendReceiveOptional[UserClaimsResponse](
        Request.get(claimsUrl.addQueryParam("id", id.toString)),
      ).map(_.map(_.claims))

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
      execute(
        Request(
          method = Method.DELETE,
          url = sessionsUrl.addQueryParam("userId", userId.toString).addQueryParam("sessionId", sessionId),
          body = Body.empty,
        ),
      ): response =>
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

    /** The call's own scope fields: what auth enforces, from the route central served. */
    private def scopeParams(caller: AntifraudCaller): List[(String, String)] = caller match
      case AntifraudCaller.Tenant(tenantId) => List("scope" -> "tenant", "callerTenantId" -> tenantId.toString)
      case AntifraudCaller.Operator => List("scope" -> "operator")

    /** The entry as the admin sent it, with the scope fields set to the caller's: the request's own `callerTenantId` (what
      * edge put there, or a client's, on a route that does not take it) is replaced, so that nothing but the route decides. */
    private def scopedBody(request: NewListEntryRequest, caller: AntifraudCaller): Body =
      val fields = request.toJsonAST match
        case Right(obj: Json.Obj) => obj.fields.filterNot((name, _) => name == "callerTenantId")
        case _ => Chunk.empty
      Body.fromString(Json.Obj(fields ++ scopeParams(caller).map((name, value) => name -> Json.Str(value))).toJson)

    override def createAntifraudListEntry(
        request: NewListEntryRequest,
        caller: AntifraudCaller,
    ): IO[AntifraudListError | Throwable, ListEntryResponse] =
      antifraud[ListEntryResponse](Request(method = Method.POST, url = antifraudListsUrl, body = scopedBody(request, caller)))

    override def previewAntifraudListEntry(
        request: NewListEntryRequest,
        caller: AntifraudCaller,
    ): IO[AntifraudListError | Throwable, PreviewResponse] =
      antifraud[PreviewResponse](Request(method = Method.POST, url = antifraudListsUrl / "preview", body = scopedBody(request, caller)))

    override def listAntifraudListEntries(
        query: ListEntriesQuery,
        caller: AntifraudCaller,
    ): IO[AntifraudListError | Throwable, List[ListEntryResponse]] =
      val params = List(
        query.listType.map("listType" -> _.toString),
        query.valueType.map("valueType" -> _.toString),
        query.tenantId.map("tenantId" -> _.toString),
        query.global.map("global" -> _.toString),
        query.limit.map("limit" -> _.toString),
        query.offset.map("offset" -> _.toString),
      ).flatten
      antifraud[List[ListEntryResponse]](Request.get(antifraudListsUrl.addQueryParams(params ++ scopeParams(caller))))

    override def deleteAntifraudListEntry(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, Unit] =
      antifraudUnit(
        Request(
          method = Method.DELETE,
          url = antifraudListsUrl.addQueryParams(List("id" -> request.id.toString, "userId" -> request.userId.toString) ++ scopeParams(caller)),
          body = Body.empty,
        ),
      )

    override def antifraudListAudit(
        entryId: Option[UUID],
        limit: Option[PageLimit],
        caller: AntifraudCaller,
    ): IO[AntifraudListError | Throwable, List[AuditRecordResponse]] =
      val params = List(entryId.map("entryId" -> _.toString), limit.map("limit" -> _.toString)).flatten
      antifraud[List[AuditRecordResponse]](Request.get((antifraudListsUrl / "audit").addQueryParams(params ++ scopeParams(caller))))

    override def getAntifraudSettings: IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
      antifraud[VersionedAntifraudSettings](Request.get(antifraudSettingsUrl))

    override def putAntifraudSettings(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
      antifraud[VersionedAntifraudSettings](Request(method = Method.PUT, url = antifraudSettingsUrl, body = Body.from(request)))

    override def patchAntifraudSettings(request: PatchAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
      antifraud[VersionedAntifraudSettings](Request(method = Method.PATCH, url = antifraudSettingsUrl, body = Body.from(request)))

    override def deleteAntifraudSettings(
        request: DeleteAntifraudSettingsRequest,
        expectedVersion: Option[Long],
    ): IO[AntifraudListError | Throwable, Unit] =
      val delete =
        Request(method = Method.DELETE, url = antifraudSettingsUrl.addQueryParams(List("userId" -> request.userId.toString)), body = Body.empty)
      antifraudUnit(expectedVersion.fold(delete)(version => delete.addHeader(Header.Custom("If-Match", EntityVersion.etag(version)))))

    override def resetAntifraudCounters(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse] =
      antifraud[ResetCountersResponse](Request(method = Method.POST, url = antifraudCountersResetUrl, body = Body.from(request)))

    override def antifraudRules: IO[AntifraudListError | Throwable, AntifraudRulesResponse] =
      antifraud[AntifraudRulesResponse](Request.get(antifraudRulesUrl))

    override def antifraudStatus: IO[AntifraudListError | Throwable, AntifraudStatusResponse] =
      antifraud[AntifraudStatusResponse](Request.get(antifraudStatusUrl))

    private def antifraud[A: JsonDecoder](request: Request): IO[AntifraudListError | Throwable, A] =
      antifraudAnswer(request): response =>
        response.body.asString.flatMap: body =>
          ZIO.fromEither(body.fromJson[A]).mapError(msg => new RuntimeException(s"Auth response decode failed: $msg"))

    private def antifraudUnit(request: Request): IO[AntifraudListError | Throwable, Unit] =
      antifraudAnswer(request)(_ => ZIO.unit)

    /** Auth's 400, 403, 404 and 412 are its answers to an entry it will not take, not failures of the call. */
    private def antifraudAnswer[A](request: Request)(success: Response => Task[A]): IO[AntifraudListError | Throwable, A] =
      execute(request): response =>
        response.status match
          case status if status.isSuccess => success(response).map(Right(_))
          case Status.BadRequest => response.body.asString.map(message => Left(AntifraudListError.Invalid(message)))
          case Status.NotFound => ZIO.succeed(Left(AntifraudListError.NotFound))
          case Status.Forbidden => response.body.asString.map(message => Left(AntifraudListError.Forbidden(message)))
          case Status.PreconditionFailed =>
            response.body.asString.map: message =>
              val current = response.headers.get("ETag").flatMap(EntityVersion.parseIfMatch(_).toOption)
              Left(AntifraudListError.StaleVersion(message, current))
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
      auditAnswer[UserAuditEventsResponse](Request.get(auditEventsUrl.addQueryParams(("userId" -> userId.toString) :: params)))

    override def getUserAuditSummary(userId: UserId, from: Option[Instant], to: Option[Instant]): IO[AuthAuditError | Throwable, Json.Obj] =
      val params = List(from.map("from" -> _.toString), to.map("to" -> _.toString)).flatten
      auditAnswer[Json.Obj](Request.get(auditSummaryUrl.addQueryParams(("userId" -> userId.toString) :: params)))

    /** Auth's 400 is its refusal of the window or the cursor; a 5xx, a 404 (an auth without the endpoint) and a connection that
      * cannot be made are auth not answering. */
    private def auditAnswer[A: JsonDecoder](request: Request): IO[AuthAuditError | Throwable, A] =
      execute(request): response =>
        response.status match
          case status if status.isSuccess =>
            response.body.asString.flatMap: body =>
              ZIO.fromEither(body.fromJson[A]).mapBoth(msg => new RuntimeException(s"Auth response decode failed: $msg"), Right(_))
          case Status.BadRequest => response.body.asString.map(message => Left(AuthAuditError.Invalid(message)))
          case status if status.isServerError || status == Status.NotFound =>
            ZIO.succeed(Left(AuthAuditError.Unavailable(s"auth answered ${status.code}")))
          case status => ZIO.fail(new RuntimeException(s"Auth call failed: ${status.code}"))
      .catchSome:
        case _: IOException => ZIO.succeed(Left(AuthAuditError.Unavailable("auth could not be reached")))
      .flatMap(ZIO.fromEither(_))

    /** The administrator the request being served came from: a call central makes on its own (the outbox) or for a provisioning
      * client names nobody, and auth records central. */
    private val admin: ZIO[Any, Nothing, Option[UserId]] =
      AuditContext.current.map(
        _.actor.collect { case (actorType, Some(id)) if actorType == CentralAudit.Actors.Admin => id }.flatMap(UserId.parse(_).toOption),
      )

    /** A `GET` or a `DELETE` to the user or the audit endpoints of auth. Their calls with a body have the field in it. */
    private def carriesActorInQuery(request: Request): Boolean =
      (request.method == Method.GET || request.method == Method.DELETE) &&
        (request.url.path.startsWith(usersUrl.path) || request.url.path.startsWith(auditUrl.path))

    private def execute[A](request: Request)(handle: Response => Task[A]): Task[A] =
      def attemptWithToken(request: Request)(token: String): Task[A] =
        withConnectionRetry:
          ZIO.scoped:
            httpClient.request(authorized(request, token)).flatMap: response =>
              if response.status == Status.Unauthorized then ZIO.fail(AuthClient.TokenExpiredError)
              else handle(response)
      for
        actor <- admin
        // On behalf of the administrator the request came from, when it is one: a call that has no body carries it in its query.
        onBehalf =
          actor.filter(_ => carriesActorInQuery(request)).fold(request)(id => request.updateURL(_.addQueryParam(AuthClient.ActorParam, id.toString)))
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
