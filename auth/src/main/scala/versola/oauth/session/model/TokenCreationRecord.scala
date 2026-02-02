package versola.oauth.session.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.user.model.UserId
import versola.util.MAC

import java.time.Instant

case class TokenCreationRecord(
    sessionId: MAC.Of[SessionId],
    userId: UserId,
    clientId: ClientId,
    scope: Set[ScopeToken],
    issuedAt: Instant,
)

