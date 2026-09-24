package versola.edge.model

import zio.Duration

case class OAuthClient(
    id: ClientId,
    credential: ClientCredential,
    permissions: Set[PermissionId],
    /** How long the access tokens this client is issued stay valid. Edge never sees a token's
      * `iat`, so this is what bounds how long a revocation of one has to be remembered. */
    accessTokenTtl: Duration,
    /** RFC 9101 §10.5: the authorization request must arrive as a signed request object, so
      * edge signs one instead of sending plain query parameters. */
    requireSignedRequestObject: Boolean = false,
    /** RFC 9126 §6.2: the authorization request must be pushed to `/par` first, so edge
      * redirects the browser to a `request_uri` rather than to the request itself. */
    requirePushedAuthorizationRequests: Boolean = false,
)
