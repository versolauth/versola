package versola.oauth.session.model

import versola.oauth.client.model.{Acr, AuthMethodRef, AuthorizationDetail, ClientId, ResourceUri, ScopeToken}
import versola.oauth.model.Nonce
import versola.oauth.model.{AccessToken, Cnf, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.MAC
import zio.prelude.Equal

import java.time.Instant

given Equal[Instant] = Equal.default

case class RefreshTokenRecord(
    /** The rotation family this token belongs to. Generated when the chain starts and
      * inherited by every successor, so a token replayed any number of generations later
      * still names the family that has to be revoked. On a rotation the stored value wins:
      * see `SessionRepository.createRefreshToken`. */
    familyId: RefreshTokenFamilyId,
    sessionId: MAC.Of[SessionId],
    publicSessionId: PublicSessionId,
    userId: UserId,
    clientId: ClientId,
    /** The resolved resource audience carried into access tokens issued from this refresh token
      * and its successors. */
    audience: List[ResourceUri],
    /** The RFC 9396 authorization details granted by the underlying grant; a refresh request
      * may ask for these or a subset of them, never for more (§6.1). `None` when the
      * underlying grant carried none, distinct from an empty list (which the parameter
      * itself disallows). */
    authorizationDetails: Option[List[AuthorizationDetail]],
    scope: Set[ScopeToken],
    issuedAt: Instant,
    expiresAt: Instant,
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    amr: Set[AuthMethodRef],
    authTime: Instant,
    acr: Option[Acr],
    /** The RFC 7800 confirmation this grant is bound to, `None` when it is unbound. A bound
      * grant may only be refreshed by presenting the same key it was issued to: an RFC 9449
      * proof carrying `jkt`, or the RFC 8705 certificate matching `x5t#S256`. */
    cnf: Option[Cnf],
) derives CanEqual, Equal
