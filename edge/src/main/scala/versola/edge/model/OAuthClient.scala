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
    /** OIDC Registration §2 `application_type`. */
    applicationType: ApplicationType = ApplicationType.web,
    /** The redirect URIs the client registered, which a native start may choose among. */
    redirectUris: Set[String] = Set.empty,
):
  /** A native app whose client authentication this edge does (#420): a `native` client edge
    * holds a certificate for. Central only allows a native client that certificate with
    * `tls_client_auth`, so holding one is the whole test. */
  def isEdgeFrontedNative: Boolean =
    applicationType == ApplicationType.native && (credential match
      case _: ClientCredential.MutualTls => true
      case _ => false)
