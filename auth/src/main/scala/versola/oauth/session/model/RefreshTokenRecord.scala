package versola.oauth.session.model

import versola.auth.model.RefreshToken
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.MAC
import zio.prelude.Equal

import java.time.Instant

given Equal[Instant] = Equal.default

case class RefreshTokenRecord(
    sessionId: MAC.Of[SessionId],
    userId: UserId,
    clientId: ClientId,
    scope: Set[ScopeToken],
    issuedAt: Instant,
    expiresAt: Instant,
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    previousRefreshToken: Option[MAC.Of[RefreshToken]],
) derives CanEqual, Equal
