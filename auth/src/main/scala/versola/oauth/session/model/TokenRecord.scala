package versola.oauth.session.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.user.model.UserId
import versola.util.MAC
import zio.prelude.Equal

import java.time.Instant

given Equal[Instant] = Equal.default

case class TokenRecord(
    sessionId: MAC.Of[SessionId],
    userId: UserId,
    clientId: ClientId,
    scope: Set[ScopeToken],
    issuedAt: Instant,
    expiresAt: Instant,
) derives CanEqual, Equal
