package versola.central.users

import versola.central.CentralConfig
import versola.central.antifraud.*
import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.http.{Body, Client, Header, MediaType, Method, QueryParams, Request, Response, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{IO, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

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

  /** The antifraud lists live in auth, which validates the entries and decides what the actor may do: its refusals are
    * [[AntifraudListError]]s, not failures of the call. */
  def createAntifraudListEntry(entry: NewListEntry, actor: AntifraudListActor): IO[AntifraudListError | Throwable, ListEntryResponse]

  def previewAntifraudListEntry(entry: NewListEntry, actor: AntifraudListActor): IO[AntifraudListError | Throwable, PreviewResponse]

  def listAntifraudListEntries(query: ListEntriesQuery, actor: AntifraudListActor): IO[AntifraudListError | Throwable, List[ListEntryResponse]]

  def deleteAntifraudListEntry(id: UUID, actor: AntifraudListActor): IO[AntifraudListError | Throwable, Unit]

  def antifraudListAudit(
      entryId: Option[UUID],
      limit: Option[Int],
      actor: AntifraudListActor,
  ): IO[AntifraudListError | Throwable, List[AuditRecordResponse]]

object AuthClient:
  val live: ZLayer[Scope & Client & CentralConfig, Throwable, AuthClient] =
    AuthTokenService.live >>> ZLayer.fromFunction(Impl(_, _, _))

  private case class AntifraudListPayload(entry: NewListEntry, actor: AntifraudListActor) derives JsonCodec

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

  private case class PatchUserClaimsPayload(id: UserId, claims: Json.Obj) derives JsonCodec

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
  ) derives JsonCodec

  private case class RenamePasskeyPayload(
      userId: UserId,
      credentialId: String,
      name: Option[String],
  ) derives JsonCodec

  private case class ResetPasswordPayload(
      userId: UserId,
      expiresInSeconds: Long,
      channel: Option[DeliveryChannel],
  ) derives JsonCodec

  private case class SetPasswordPayload(
      userId: UserId,
      password: String,
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
    private val configSyncUrl: URL = config.auth.url / "service" / "configuration" / "sync"

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
      send(
        Request(
          method = Method.PATCH,
          url = claimsUrl,
          body = Body.from(PatchUserClaimsPayload(id, patch)),
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
      send(
        Request(
          method = Method.POST,
          url = limitsResetUrl,
          body = Body.from(ResetUserLimitsPayload(userId, tenantId, email, phone)),
        ),
      )

    override def listPasskeys(userId: UserId): Task[List[PasskeyInfo]] =
      sendReceive[ListPasskeysResponse](
        Request.get(passkeysUrl.addQueryParam("id", userId.toString)),
      ).map(_.passkeys)

    override def renamePasskey(userId: UserId, credentialId: String, name: Option[String]): Task[Unit] =
      send(
        Request(
          method = Method.PATCH,
          url = passkeysUrl,
          body = Body.from(RenamePasskeyPayload(userId, credentialId, name)),
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
      sendReceiveOptional[ResetPasswordResponse](
        Request(
          method = Method.POST,
          url = passwordResetUrl,
          body = Body.from(ResetPasswordPayload(userId, expiresInSeconds, channel)),
        ),
      ).map(_.map(_.password))

    override def setPassword(userId: UserId, password: String): Task[Unit] =
      send(
        Request(
          method = Method.POST,
          url = passwordSetUrl,
          body = Body.from(SetPasswordPayload(userId, password)),
        ),
      )

    override def createAntifraudListEntry(entry: NewListEntry, actor: AntifraudListActor): IO[AntifraudListError | Throwable, ListEntryResponse] =
      antifraud[ListEntryResponse](
        Request(method = Method.POST, url = antifraudListsUrl, body = Body.from(AntifraudListPayload(entry, actor))),
      )

    override def previewAntifraudListEntry(entry: NewListEntry, actor: AntifraudListActor): IO[AntifraudListError | Throwable, PreviewResponse] =
      antifraud[PreviewResponse](
        Request(method = Method.POST, url = antifraudListsUrl / "preview", body = Body.from(AntifraudListPayload(entry, actor))),
      )

    override def listAntifraudListEntries(
        query: ListEntriesQuery,
        actor: AntifraudListActor,
    ): IO[AntifraudListError | Throwable, List[ListEntryResponse]] =
      val params = List(
        query.listType.map("listType" -> _.toString),
        query.valueType.map("valueType" -> _.toString),
        query.tenantId.map("tenantId" -> _.toString),
        query.global.map("global" -> _.toString),
        query.limit.map("limit" -> _.toString),
        query.offset.map("offset" -> _.toString),
      ).flatten
      antifraud[List[ListEntryResponse]](Request.get(withActor(antifraudListsUrl, params, actor)))

    override def deleteAntifraudListEntry(id: UUID, actor: AntifraudListActor): IO[AntifraudListError | Throwable, Unit] =
      antifraudUnit(Request(method = Method.DELETE, url = withActor(antifraudListsUrl, List("id" -> id.toString), actor), body = Body.empty))

    override def antifraudListAudit(
        entryId: Option[UUID],
        limit: Option[Int],
        actor: AntifraudListActor,
    ): IO[AntifraudListError | Throwable, List[AuditRecordResponse]] =
      val params = List(entryId.map("entryId" -> _.toString), limit.map("limit" -> _.toString)).flatten
      antifraud[List[AuditRecordResponse]](Request.get(withActor(antifraudListsUrl / "audit", params, actor)))

    private def withActor(url: URL, params: List[(String, String)], actor: AntifraudListActor): URL =
      url.addQueryParams(params ++ List("actor" -> actor.name) ++ actor.tenantId.map("actorTenantId" -> _.toString))

    private def antifraud[A: JsonDecoder](request: Request): IO[AntifraudListError | Throwable, A] =
      antifraudAnswer(request): response =>
        response.body.asString.flatMap: body =>
          ZIO.fromEither(body.fromJson[A]).mapError(msg => new RuntimeException(s"Auth response decode failed: $msg"))

    private def antifraudUnit(request: Request): IO[AntifraudListError | Throwable, Unit] =
      antifraudAnswer(request)(_ => ZIO.unit)

    /** Auth's 400, 403 and 404 are its answers to an entry or an actor it will not take, not failures of the call. */
    private def antifraudAnswer[A](request: Request)(success: Response => Task[A]): IO[AntifraudListError | Throwable, A] =
      execute(request): response =>
        response.status match
          case status if status.isSuccess => success(response).map(Right(_))
          case Status.BadRequest => response.body.asString.map(message => Left(AntifraudListError.Invalid(message)))
          case Status.Forbidden => response.body.asString.map(message => Left(AntifraudListError.Forbidden(message)))
          case Status.NotFound => ZIO.succeed(Left(AntifraudListError.NotFound))
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

    private def execute[A](request: Request)(handle: Response => Task[A]): Task[A] =
      def attemptWithToken(token: String): Task[A] =
        withConnectionRetry:
          ZIO.scoped:
            httpClient.request(authorized(request, token)).flatMap: response =>
              if response.status == Status.Unauthorized then ZIO.fail(AuthClient.TokenExpiredError)
              else handle(response)
      for
        token <- tokenService.getToken
        result <- attemptWithToken(token).catchSome:
          case AuthClient.TokenExpiredError =>
            tokenService.refreshToken().flatMap(attemptWithToken)
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
