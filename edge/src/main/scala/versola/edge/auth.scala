package versola.edge

import versola.edge.model.{ClientId, RoleId, TenantId}
import versola.util.JWT
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.{JsonCodec, jsonField}

/** Authenticates a caller of the edge's own endpoints, accepting either the
  * `EDGE_SESSION` cookie (browser) or an `Authorization: Bearer` token (mobile),
  * validates it against auth's JWKS and returns its claims.
  */
def authorize(request: Request): ZIO[JwksService, Unauthorized.type, PermissionsClaims] =
  val token = request.header(Header.Authorization)
    .collect { case Header.Authorization.Bearer(bearer) => bearer.stringValue }
    .orElse(request.cookie(EdgeSessionCookie.name).map(_.content))

  token match
    case Some(raw) =>
      for
        jwksService <- ZIO.service[JwksService]
        keys        <- jwksService.getPublicKeys
        claims      <- JWT.deserialize[PermissionsClaims](raw, keys, JWT.Type.AccessToken)
          .orElseFail(Unauthorized)
      yield claims

    case None =>
      ZIO.fail(Unauthorized)

case class PermissionsClaims(
    @jsonField("client_id") clientId: Option[ClientId],
    @jsonField("tenant_id") tenantId: Option[TenantId],
    roles: Option[List[RoleId]],
) derives JsonCodec
