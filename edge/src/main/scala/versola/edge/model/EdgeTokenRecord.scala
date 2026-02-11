package versola.edge.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.MAC
import zio.prelude.Equal
import zio.schema.*

import java.time.Instant

given Equal[Instant] = Equal.default

/**
 * Token binding record
 * Links edge session to access/refresh tokens from OAuth provider
 */
case class EdgeTokenRecord(
    sessionId: MAC.Of[EdgeSessionId],
    clientId: ClientId,
    accessTokenHash: MAC,
    refreshTokenHash: Option[MAC],
    scope: Set[ScopeToken],
    issuedAt: Instant,
    expiresAt: Instant,
) derives Schema, CanEqual, Equal

