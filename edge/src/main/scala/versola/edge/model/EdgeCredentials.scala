package versola.edge.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.Secret
import zio.schema.*

/**
 * Edge service client credentials configuration
 * Supports multiple client_ids per service instance
 */
case class EdgeCredentials(
    clientId: ClientId,
    clientSecretHash: Secret,
    providerUrl: String,
    scopes: Set[ScopeToken],
    createdAt: java.time.Instant,
) derives Schema

