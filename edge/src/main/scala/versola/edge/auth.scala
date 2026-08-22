package versola.edge

import versola.edge.model.{AccessTokenId, ClientId, RoleId, SessionId, TenantId}
import versola.edge.revocation.{RevocationKey, TokenRevocationService}
import versola.util.JWT
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.{JsonCodec, jsonField}

import java.time.Instant

/** Authenticates a caller of the edge's own endpoints, accepting either the
  * `EDGE_SESSION` cookie (browser) or an `Authorization: Bearer` token (mobile),
  * validates it against auth's JWKS and returns its claims.
  */
def authorize(request: Request): ZIO[JwksService & TokenRevocationService, Unauthorized.type, PermissionsClaims] =
  val token = request.header(Header.Authorization)
    .collect { case Header.Authorization.Bearer(bearer) => bearer.stringValue }
    .orElse(request.cookie(EdgeSessionCookie.name).map(c => EdgeSessionCookie.parse(c.content)._2))

  token match
    case Some(raw) =>
      for
        jwksService <- ZIO.service[JwksService]
        keys        <- jwksService.getPublicKeys
        claims      <- JWT.deserialize[PermissionsClaims](raw, keys, JWT.Type.AccessToken)
          .orElseFail(Unauthorized)
        // The edge's own endpoints answer on the same tokens it proxies with, so a revoked
        // one must not be accepted here either.
        revocationService <- ZIO.service[TokenRevocationService]
        revoked     <- revocationService.isRevoked(
          RevocationKey.of(claims.jti, claims.sid, claims.subject),
          Instant.ofEpochSecond(claims.issuedAt),
        )
        _           <- ZIO.fail(Unauthorized).when(revoked)
      yield claims

    case None =>
      ZIO.fail(Unauthorized)

case class PermissionsClaims(
    @jsonField("jti") jti: AccessTokenId,
    @jsonField("sub") subject: String,
    @jsonField("iat") issuedAt: Long,
    @jsonField("client_id") clientId: Option[ClientId],
    @jsonField("tenant_id") tenantId: Option[TenantId],
    roles: Option[List[RoleId]],
    @jsonField("sid") sid: Option[SessionId] = None,
) derives JsonCodec
