package versola.central.users

import versola.central.CentralConfig
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.http.{Body, Client, Header, MediaType, Method, Request, Response, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Schedule, Scope, Task, ZIO, ZLayer, durationInt}

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

  def getUserClaims(id: UserId): Task[Option[Json.Obj]]

  def patchUserClaims(id: UserId, patch: Json.Obj): Task[Unit]

  def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

  def getUserSessions(id: UserId): Task[List[AuthClient.SessionDto]]

  def invalidateSession(userId: UserId): Task[Unit]
  def resetUserLimits(
      userId: UserId,
      tenantId: TenantId,
      email: Option[Email],
      phone: Option[Phone],
  ): Task[Unit]

  def listPasskeys(userId: UserId): Task[List[PasskeyInfo]]

  def renamePasskey(userId: UserId, credentialId: String, name: Option[String]): Task[Unit]

  def deletePasskey(userId: UserId, credentialId: String): Task[Unit]

  def resetPassword(request: ResetPasswordRequest): Task[Unit]

object AuthClient:
  val live: ZLayer[Scope & Client & CentralConfig, Throwable, AuthClient] =
    AuthTokenService.live >>> ZLayer.fromFunction(Impl(_, _, _))

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

  case class SessionDto(
      clientId: String,
      userAgent: Option[String],
      createdAt: String,
  ) derives JsonCodec

  case class SessionListResponse(sessions: List[SessionDto]) derives JsonCodec

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

  class Impl(
      httpClient: Client,
      config: CentralConfig,
      tokenService: AuthTokenService,
  ) extends AuthClient:
    private val usersUrl: URL = config.auth.url / "users"
    private val rolesUrl: URL = usersUrl / "roles"
    private val claimsUrl: URL = usersUrl / "claims"
    private val sessionsUrl: URL = usersUrl / "sessions"
    private val limitsResetUrl: URL = usersUrl / "limits" / "reset"
    private val passkeysUrl: URL = usersUrl / "passkeys"
    private val passwordResetUrl: URL = usersUrl / "password" / "reset"

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

    override def invalidateSession(userId: UserId): Task[Unit] =
      send(
        Request(
          method = Method.DELETE,
          url = sessionsUrl.addQueryParam("userId", userId.toString),
          body = Body.empty,
        ),
      )
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

    override def resetPassword(request: ResetPasswordRequest): Task[Unit] =
      send(
        Request(
          method = Method.POST,
          url = passwordResetUrl,
          body = Body.from(request),
        ),
      )

    private def execute[A](request: Request)(handle: Response => Task[A]): Task[A] =
      for
        token <- tokenService.getToken
        authorized = request
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader(Header.Authorization.Bearer(token))
        result <- withConnectionRetry(ZIO.scoped:
          httpClient.request(authorized).flatMap(handle))
      yield result

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
