package versola.edge

import versola.edge.dpop.DpopVerifier
import versola.edge.model.{AccessTokenId, ClientId, Confirmation, RefreshTokenFamilyId, RoleId, SessionId, TenantId}
import versola.edge.revocation.{RevocationKey, TokenRevocationService}
import versola.util.JWT
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.{JsonCodec, jsonField}

import java.time.Instant

/** Every way `authorize` can refuse a caller. Unlike the proxy path (`EdgeService.Outcome`),
  * this used to collapse straight to `util.http.Unauthorized` -- fine for a bare 401, but a
  * `NonceRequired` refusal here has nowhere to put the nonce the client needs to retry with,
  * so it silently regressed to a dead end (§9's challenge issued, then never honoured, because
  * an opaque `Unauthorized.type` cannot carry it back out). The route this feeds
  * (`EdgeController.permissionsEndpoint`) turns `NonceRequired` into the same `DPoP-Nonce`
  * response the proxy already gives, and re-raises `Denied` as `Unauthorized` unchanged.
  */
enum AuthorizeOutcome extends RuntimeException, scala.util.control.NoStackTrace:
  case Denied
  case NonceRequired(nonce: String)

/** Authenticates a caller of the edge's own endpoints, accepting the `EDGE_SESSION` cookie
  * (browser) or an `Authorization: Bearer`/`DPoP` token (mobile), validates it against auth's
  * JWKS and returns its claims.
  *
  * These endpoints answer on the same tokens the proxy accepts, so they have to demand the
  * same thing of them: a token carrying `cnf.jkt` is only honoured here with a valid RFC 9449
  * proof, and one carrying `cnf.x5t#S256` only out of the session cookie, or
  * `/permissions/me` would be a way around the binding the proxy enforces.
  */
def authorize(
    request: Request,
): ZIO[JwksService & TokenRevocationService & DpopVerifier, AuthorizeOutcome, PermissionsClaims] =
  val presented = request.header(Header.Authorization)
    .collect {
      case Header.Authorization.Bearer(bearer) => (bearer.stringValue, Presentation.Bearer)
      case Header.Authorization.Unparsed(scheme, token) if scheme.equalsIgnoreCase(DpopVerifier.Scheme) =>
        (token.stringValue, Presentation.Dpop)
    }
    .orElse(
      request.cookie(EdgeSessionCookie.name)
        .map(c => (EdgeSessionCookie.parse(c.content)._2, Presentation.Cookie)),
    )

  presented match
    case Some((raw, presentation)) =>
      for
        jwksService <- ZIO.service[JwksService]
        keys <- jwksService.getPublicKeys
        claims <- JWT.deserialize[PermissionsClaims](raw, keys, JWT.Type.AccessToken)
          .orElseFail(AuthorizeOutcome.Denied)
        // The edge's own endpoints answer on the same tokens it proxies with, so a revoked
        // one must not be accepted here either.
        revocationService <- ZIO.service[TokenRevocationService]
        revoked <- revocationService.isRevoked(
          RevocationKey.of(claims.jti, claims.family, claims.sid, claims.subject),
          Instant.ofEpochSecond(claims.issuedAt),
        )
        _ <- ZIO.fail(AuthorizeOutcome.Denied).when(revoked)
        _ <- verifyBinding(request, raw, claims, presentation)
      yield claims

    case None =>
      ZIO.fail(AuthorizeOutcome.Denied)

/** RFC 9449 §7.1/§7.2 and RFC 8705 §3 for the edge's own endpoints. Nonce enforcement is
  * unconditional (`DpopVerifier.checkNonce`), so this has exactly one challenge to negotiate,
  * the same as the proxy's -- everything else here (missing proof, bad binding, a downgrade)
  * has nothing for the client to retry differently and collapses to the same bare refusal.
  *
  * Which of these applies is decided by `TokenBinding.check`, the same function the proxy's
  * `checkTokenBinding` uses, so a token bound to both a key and a certificate is refused here
  * on every presentation too.
  */
private def verifyBinding(
    request: Request,
    accessToken: String,
    claims: PermissionsClaims,
    presentation: Presentation,
): ZIO[DpopVerifier, AuthorizeOutcome, Unit] =
  TokenBinding.check(claims.confirmation, presentation) match
    case BindingCheck.VerifyProof(jkt) =>
      ZIO.serviceWithZIO[DpopVerifier]: verifier =>
        DpopVerifier.proofHeader(request)
          .orElseFail(AuthorizeOutcome.Denied)
          .flatMap: proof =>
            verifier.verify(
              proofHeader = proof,
              accessToken = accessToken,
              boundKeyThumbprint = jkt,
              method = request.method,
              path = request.url.path,
            ).mapError {
              // §9: the one refusal here a client can act on -- everything else collapses to
              // the same bare 401 the proxy's own `Outcome.InvalidDpopProof` answers with.
              case DpopVerifier.Error.NonceRequired(nonce) => AuthorizeOutcome.NonceRequired(nonce)
              case _ => AuthorizeOutcome.Denied
            }
          .unit
    case BindingCheck.Accept => ZIO.unit
    case BindingCheck.Downgrade | BindingCheck.CertificateOutsideSession | BindingCheck.UnboundDpop =>
      ZIO.fail(AuthorizeOutcome.Denied)

case class PermissionsClaims(
    @jsonField("jti") jti: AccessTokenId,
    @jsonField("sub") subject: String,
    @jsonField("iat") issuedAt: Long,
    @jsonField("client_id") clientId: Option[ClientId],
    @jsonField("tenant_id") tenantId: Option[TenantId],
    roles: Option[List[RoleId]],
    @jsonField("sid") sid: Option[SessionId] = None,
    @jsonField("fam") family: Option[RefreshTokenFamilyId] = None,
    @jsonField("cnf") confirmation: Option[Confirmation],
) derives JsonCodec
