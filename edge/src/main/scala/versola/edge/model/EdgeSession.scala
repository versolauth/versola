package versola.edge.model

import versola.oauth.client.model.ClientId
import zio.schema.*

import java.time.Instant

/**
 * Edge-side session record
 * Represents a client session on the edge service
 * Separate from server-side OAuth sessions
 */
case class EdgeSession(
    clientId: ClientId,
    userIdentifier: String,
    state: Option[String],
    createdAt: Instant,
    expiresAt: Instant,
) derives Schema

