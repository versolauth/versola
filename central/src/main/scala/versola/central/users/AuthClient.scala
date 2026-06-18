package versola.central.users

import versola.central.CentralConfig
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.http.{Body, Client, Header, MediaType, Method, Request, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec}
import zio.{Scope, Task, ZIO, ZLayer}

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

  def resetUserLimits(
      userId: UserId,
      tenantId: TenantId,
      email: Option[Email],
      phone: Option[Phone],
  ): Task[Unit]

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

  private case class ResetUserLimitsPayload(
      userId: UserId,
      tenantId: TenantId,
      email: Option[Email],
      phone: Option[Phone],
  ) derives JsonCodec

  class Impl(
      httpClient: Client,
      config: CentralConfig,
      tokenService: AuthTokenService,
  ) extends AuthClient:
    private val usersUrl: URL = config.auth.url / "users"
    private val rolesUrl: URL = usersUrl / "roles"
    private val claimsUrl: URL = usersUrl / "claims"
    private val limitsResetUrl: URL = usersUrl / "limits" / "reset"

    override def upsertUser(
        id: UserId,
        version: UUID,
        email: Option[Email],
        phone: Option[Phone],
        login: Option[Login],
    ): Task[Unit] =
      send(mutating(Method.PUT, usersUrl, UpsertUserPayload(id, version, email, phone, login).toJson))

    override def updateUserRoles(
        userId: UserId,
        tenantId: TenantId,
        add: Set[RoleId],
        remove: Set[RoleId],
    ): Task[Unit] =
      send(mutating(Method.PATCH, rolesUrl, UpdateUserRolesPayload(userId, tenantId, add, remove).toJson))

    override def getUserClaims(id: UserId): Task[Option[Json.Obj]] =
      for
        token <- tokenService.getToken
        request = Request.get(claimsUrl.addQueryParam("id", id.toString))
          .addHeader(Header.Authorization.Bearer(token))
        result <- withConnectionRetry(ZIO.scoped:
          httpClient.request(request).flatMap: response =>
            response.status match
              case Status.NoContent => ZIO.none
              case s if s.isSuccess =>
                response.body.asString.flatMap: body =>
                  ZIO.fromEither(body.fromJson[UserClaimsResponse])
                    .mapError(msg => new RuntimeException(s"Auth claims decode failed: $msg"))
                    .map(r => Some(r.claims))
              case s =>
                response.body.asString.flatMap: body =>
                  ZIO.fail(new RuntimeException(s"Auth call failed: ${s.code} $body")))
      yield result

    override def patchUserClaims(id: UserId, patch: Json.Obj): Task[Unit] =
      send(mutating(Method.PATCH, claimsUrl, PatchUserClaimsPayload(id, patch).toJson))

    override def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]] =
      for
        token <- tokenService.getToken
        request = Request.get(rolesUrl.addQueryParam("id", id.toString).addQueryParam("tenantId", tenantId))
          .addHeader(Header.Authorization.Bearer(token))
        result <- withConnectionRetry(ZIO.scoped:
          httpClient.request(request).flatMap: response =>
            if response.status.isSuccess then
              response.body.asString.flatMap: body =>
                ZIO.fromEither(body.fromJson[UserRolesResponse])
                  .mapError(msg => new RuntimeException(s"Auth roles decode failed: $msg"))
                  .map(_.roles)
            else
              response.body.asString.flatMap: body =>
                ZIO.fail(new RuntimeException(s"Auth call failed: ${response.status.code} $body")))
      yield result

    override def resetUserLimits(
        userId: UserId,
        tenantId: TenantId,
        email: Option[Email],
        phone: Option[Phone],
    ): Task[Unit] =
      send(mutating(Method.POST, limitsResetUrl, ResetUserLimitsPayload(userId, tenantId, email, phone).toJson))

    /** Retries once on transient connection failures (stale pooled channel after auth restart). */
    private def withConnectionRetry[A](effect: Task[A]): Task[A] =
      effect.catchSome {
        case _: java.net.ConnectException => effect
        case _: java.io.IOException       => effect
      }

    private def jsonBody(json: String): Body =
      Body.fromString(json)

    private def mutating(method: Method, url: URL, json: String): Request =
      Request(method = method, url = url, body = jsonBody(json))

    private def send(request: Request): Task[Unit] =
      for
        token <- tokenService.getToken
        authorized = request
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader(Header.Authorization.Bearer(token))
        _ <- ZIO.logDebug(s"Sending ${authorized.method} ${authorized.url}")
        _ <- ZIO.scoped:
          httpClient.request(authorized).flatMap: response =>
            if response.status.isSuccess then ZIO.unit
            else
              response.body.asString.flatMap: body =>
                ZIO.logError(s"Auth call failed: ${response.status.code} $body") *>
                  ZIO.fail(new RuntimeException(s"Auth call failed: ${response.status.code} $body"))
      yield ()
