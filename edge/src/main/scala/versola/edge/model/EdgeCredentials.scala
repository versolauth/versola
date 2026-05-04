package versola.edge.model

import versola.util.Secret
import zio.schema.*

/**
 * Edge client credentials configuration
 * Represents OAuth client configuration for a specific provider
 */
case class EdgeCredentials(
    clientId: ClientId,
    clientSecret: Secret,
    providerUrl: String,
    scopes: Set[ScopeToken],
) derives Schema

