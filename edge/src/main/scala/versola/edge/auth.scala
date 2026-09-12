package versola.edge

import versola.edge.dpop.DpopVerifier
import versola.edge.model.{AccessTokenId, ClientId, Confirmation, RoleId, SessionId, TenantId}
import versola.edge.revocation.{RevocationKey, TokenRevocationService}
import versola.util.JWT
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.{JsonCodec, jsonField}

import java.time.Instant

/** Authenticates a caller of the edge's own endpoints, accepting the `EDGE_SESSION` cookie
  * (browser) or an `Authorization: Bearer`/`DPoP` token (mobile), validates it against auth's
  * JWKS and returns its claims.
  *
  * These endpoints answer on the same tokens the proxy accepts, so they have to demand the
  * same thing of them: a token carrying `cnf.jkt` is only honoured here with a valid RFC 9449
  * proof, or `/permissions/me` would be a way around the binding the proxy enforces.
  */
def authorize(
    request: Request,
): ZIO[JwksService & TokenRevocationService & DpopVerifier, Unauthorized.type, PermissionsClaims] =
  val presented = request.header(Header.Authorization)
    .collect {
      case Header.Authorization.Bearer(bearer) => (bearer.stringValue, false)
      case Header.Authorization.Unparsed(scheme, token) if scheme.equalsIgnoreCase(DpopVerifier.Scheme) =>
        (token.stringValue, true)
    }
    .orElse(
      request.cookie(EdgeSessionCookie.name)
        .map(c => (EdgeSessionCookie.parse(c.content)._2, false)),
    )

  presented match
    case Some((raw, dpopScheme)) =>
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
        _           <- verifyProof(request, raw, claims, dpopScheme)
      yield claims

    case None =>
      ZIO.fail(Unauthorized)

/** RFC 9449 §7.1/§7.2 for the edge's own endpoints. Unlike the proxy, these have no
  * challenge to negotiate over -- there is nothing here for a client to retry differently, a
  * nonce included -- so every failure collapses to the same refusal.
  */
private def verifyProof(
    request: Request,
    accessToken: String,
    claims: PermissionsClaims,
    dpopScheme: Boolean,
): ZIO[DpopVerifier, Unauthorized.type, Unit] =
  (claims.confirmation.map(_.jkt), dpopScheme) match
    case (Some(jkt), true) =>
      ZIO.serviceWithZIO[DpopVerifier]: verifier =>
        ZIO.fromOption(request.rawHeader(DpopVerifier.Scheme))
          .orElseFail(Unauthorized)
          .flatMap: proof =>
            verifier.verify(
              proofHeader = proof,
              accessToken = accessToken,
              boundKeyThumbprint = jkt,
              method = request.method,
              path = request.url.path,
            ).mapError(_ => Unauthorized)
          .unit
    // §7.2: a key-bound token presented without proof of that key, here as in the proxy.
    case (Some(_), false) => ZIO.fail(Unauthorized)
    case (None, true) => ZIO.fail(Unauthorized)
    case (None, false) => ZIO.unit

case class PermissionsClaims(
    @jsonField("jti") jti: AccessTokenId,
    @jsonField("sub") subject: String,
    @jsonField("iat") issuedAt: Long,
    @jsonField("client_id") clientId: Option[ClientId],
    @jsonField("tenant_id") tenantId: Option[TenantId],
    roles: Option[List[RoleId]],
    @jsonField("sid") sid: Option[SessionId] = None,
    @jsonField("cnf") confirmation: Option[Confirmation] = None,
) derives JsonCodec
