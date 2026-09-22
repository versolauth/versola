package versola.central.configuration.clients

import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{JsonWebKeySet, RedirectUri, Secret}
import zio.http.URL
import zio.prelude.Equal
import zio.schema.*
import zio.{Duration, NonEmptyChunk}

given Schema[URL] = Schema.primitive[String].transformOrFail(
  string => URL.decode(string).left.map(_.getMessage),
  url => Right(url.encode),
)
given Equal[URL] = (a, b) => a == b

case class OAuthClientRecord(
    id: ClientId,
    tenantId: TenantId,
    clientName: Map[String, String],
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    secret: Option[Secret],
    previousSecret: Option[Secret],
    accessTokenTtl: Duration,
    refreshTokenTtl: Duration,
    permissions: Set[Permission],
    theme: String,
    authFlow: Option[AuthFlow],
    registrationFlow: Option[RegistrationFlow],
    otpTemplateId: String,
    frontChannelLogoutUri: Option[URL],
    frontChannelLogoutSessionRequired: Boolean,
    backChannelLogoutUri: Option[URL],
    logoUri: Option[String],
    policyUri: Option[String],
    tosUri: Option[String],
    consentFlow: Option[ConsentFlow],
    /** RFC 9449 §5.2 `dpop_bound_access_tokens`: the client always uses DPoP, so a token
      * request from it without a proof is refused rather than answered with a bearer token. */
    dpopBoundAccessTokens: Boolean,
    /** RFC 8705 §2.1 mutual-TLS client authentication; `None` when the client authenticates
      * with a secret. */
    mtlsAuth: Option[MutualTlsAuth],
    /** RFC 8705 §3.4 `tls_client_certificate_bound_access_tokens`, as the client
      * registered it. Only clients that authenticate some other way have a say: read
      * [[bindsAccessTokens]] rather than this field to find out whether a token gets a
      * `cnf` claim. */
    certificateBoundAccessTokens: Boolean,
    /** RFC 7523 §2.2 `private_key_jwt`: the public keys this client signs its client
      * assertions with, and the only keys an assertion from it is verified against. `None`
      * when the client does not use the method.
      *
      * Mutually exclusive with [[mtlsAuth]] — a client authenticates one way, and registering
      * a second credential only widens what a single compromise reaches. */
    jwks: Option[JsonWebKeySet],
    /** RFC 9101 §10.5 `require_signed_request_object`: the client states its authorization
      * request in a request object it signed, so a plain parameter set from it is refused
      * rather than answered. Requires [[jwks]], the only keys such an object is verified
      * against. */
    requireSignedRequestObject: Boolean,
    /** RFC 9126 §6.2 `require_pushed_authorization_requests`: the client pushes its
      * authorization request to `/par` first, so a request that arrives at `/authorize`
      * without a `request_uri` is refused rather than answered. */
    requirePushedAuthorizationRequests: Boolean,
) derives Schema, CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  /** Whether an access token issued to this client carries an RFC 8705 §3 `x5t#S256`
    * confirmation. Authenticating with a certificate implies it: the certificate is
    * already validated at the token endpoint, so handing back a token that anyone who
    * steals it can replay throws away the only property mutual TLS was bought for.
    * The registered flag therefore only decides the case the RFC separates §3 for — a
    * client authenticating by secret or (once it exists) `private_key_jwt` that still
    * presents a certificate purely to have its tokens bound.
    */
  def bindsAccessTokens: Boolean = mtlsAuth.nonEmpty || certificateBoundAccessTokens

  def isPublic: Boolean = !isConfidential

  def hasPermission(permission: Permission): Boolean = permissions.contains(permission)
