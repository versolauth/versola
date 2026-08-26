package versola.edge.model

import versola.util.Secret
import zio.Duration

case class OAuthClient(
    id: ClientId,
    secret: Secret,
    permissions: Set[PermissionId],
    /** How long the access tokens this client is issued stay valid. Edge never sees a token's
      * `iat`, so this is what bounds how long a revocation of one has to be remembered. */
    accessTokenTtl: Duration,
)
