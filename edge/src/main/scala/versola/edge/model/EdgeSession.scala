package versola.edge.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import zio.schema.*

import java.time.Instant

/**
 * Edge-side session record
 * Represents a client session on the edge service with embedded OAuth tokens
 * Combines session state and encrypted tokens in a single record
 */
case class EdgeSession(
    clientId: ClientId,
    state: Option[String],
    accessTokenEncrypted: String,
    refreshTokenEncrypted: Option[String],
    tokenExpiresAt: Instant,
    scope: Set[ScopeToken],
    createdAt: Instant,
    sessionExpiresAt: Instant,
) derives Schema

