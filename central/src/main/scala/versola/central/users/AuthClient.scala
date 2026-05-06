package versola.central.users

import versola.central.CentralConfig
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.http.{Body, Client, Header, MediaType, Method, Request, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec}
import zio.{Scope, Task, ZIO, ZLayer}

/** HTTP client for the auth service. Methods mirror the auth-side user/role endpoints. */
trait AuthClient:
  def createUser(
      id: UserId,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
      claims: Json.Obj,
  ): Task[Unit]

  def patchUser(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
      claims: Option[Json.Obj],
  ): Task[Unit]

  def assignRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit]

  def removeRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit]

  def getUserClaims(id: UserId): Task[Option[Json.Obj]]

  def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]]

object AuthClient:
  val live: ZLayer[Scope & Client & CentralConfig, Throwable, AuthClient] =
    AuthTokenService.live >>> ZLayer.fromFunction(Impl(_, _, _))

  private case class CreateUserPayload(
      id: UserId,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
      claims: Json.Obj,
  ) derives JsonCodec

  private case class PatchUserPayload(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
      claims: Option[Json.Obj],
  ) derives JsonCodec

  private case class RolePayload(userId: UserId, tenantId: TenantId, roleId: RoleId) derives JsonCodec

  private case class UserClaimsResponse(claims: Json.Obj) derives JsonCodec

  private case class UserRolesResponse(roles: List[RoleId]) derives JsonCodec

  class Impl(
      httpClient: Client,
      config: CentralConfig,
      tokenService: AuthTokenService,
  ) extends AuthClient:
    private val usersUrl: URL = config.auth.url / "users"
    private val rolesUrl: URL = usersUrl / "roles"
    private val claimsUrl: URL = usersUrl / "claims"

    override def createUser(
        id: UserId,
        email: Option[Email],
        phone: Option[Phone],
        login: Option[Login],
        claims: Json.Obj,
    ): Task[Unit] =
      send(Request.post(usersUrl, jsonBody(CreateUserPayload(id, email, phone, login, claims).toJson)))

    override def patchUser(
        id: UserId,
        email: Option[Patch[Email]],
        phone: Option[Patch[Phone]],
        login: Option[Patch[Login]],
        claims: Option[Json.Obj],
    ): Task[Unit] =
      send(mutating(Method.PATCH, usersUrl, PatchUserPayload(id, email, phone, login, claims).toJson))

    override def assignRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] =
      send(Request.post(rolesUrl, jsonBody(RolePayload(userId, tenantId, roleId).toJson)))

    override def removeRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] =
      send(mutating(Method.DELETE, rolesUrl, RolePayload(userId, tenantId, roleId).toJson))

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
