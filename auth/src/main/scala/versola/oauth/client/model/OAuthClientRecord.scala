package versola.oauth.client.model

import versola.util.{Dpop, JsonWebKeySet, Secret}
import zio.Duration
import zio.http.URL
import zio.json.{JsonCodec, JsonEncoder}
import zio.prelude.{Equal, NonEmptySet}
import zio.schema.*

given Equal[URL] = (a, b) => a == b

case class OAuthClientRecord(
    id: ClientId,
    tenantId: TenantId,
    clientName: Map[String, String],
    redirectUris: NonEmptySet[String],
    scope: Set[ScopeToken],
    secret: Option[Secret],
    previousSecret: Option[Secret],
    accessTokenTtl: Duration,
    refreshTokenTtl: Duration,
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
    /** Consent screen configuration; `None` for first-party clients, which never prompt. */
    consentFlow: Option[ConsentFlow],
    /** RFC 9449 §5.2 `dpop_bound_access_tokens`: the client always uses DPoP, so a token
      * request from it that carries no proof is refused rather than answered with a bearer
      * token. */
    dpopBoundAccessTokens: Boolean,
    /** RFC 9449 §5.1: the signing algorithms a proof from this client may use, narrowing
      * `dpop_signing_alg_values_supported`. Empty means the client registered no narrowing and
      * is held to whatever the metadata document advertises. */
    dpopSigningAlgs: Set[Dpop.Algorithm],
    /** The modulus length an RSA DPoP proof key from this client must reach. `None` leaves the
      * RFC 7518 §3.3 floor [[Dpop.KeyPolicy.MinRsaKeySize]], which applies either way -- the
      * registered value can only raise it. */
    dpopMinRsaKeySize: Option[Int],
    /** RFC 8705 mutual-TLS client authentication, and which of its two methods; `None` when
      * the client authenticates with a secret. [[MutualTlsAuth.SelfSignedTlsClientAuth]]
      * matches the presented certificate against [[jwks]], so the two are set together for
      * that method and apart for every other. */
    mtlsAuth: Option[MutualTlsAuth],
    /** RFC 8705 §3.4 `tls_client_certificate_bound_access_tokens`, as the client
      * registered it. Only clients that authenticate some other way have a say: read
      * [[bindsAccessTokens]] rather than this field to find out whether a token gets a
      * `cnf` claim. */
    certificateBoundAccessTokens: Boolean,
    /** The public keys this client registered, read by whichever method [[mtlsAuth]] says is
      * in force: RFC 7523 §2.2 `private_key_jwt` verifies its client assertions against them
      * when no `mtlsAuth` is registered, and RFC 8705 §2.2 `self_signed_tls_client_auth`
      * matches its certificate's public key against them when that method is. `None` when the
      * client uses neither.
      *
      * Never set alongside [[MutualTlsAuth.TlsClientAuth]], which registration enforces:
      * a subject-matched certificate and a registered key set would be two credentials for
      * one client. */
    jwks: Option[JsonWebKeySet],
    /** RFC 9101 §10.5 `require_signed_request_object`: the client states its authorization
      * request in a request object it signed, so a plain parameter set from it is refused
      * rather than answered. */
    requireSignedRequestObject: Boolean,
    /** RFC 9126 §6.2 `require_pushed_authorization_requests`: the client pushes its
      * authorization request to `/par` first, so one that arrives at `/authorize` without a
      * `request_uri` is refused rather than answered. */
    requirePushedAuthorizationRequests: Boolean,
) derives CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  /** Whether an access token issued to this client carries an RFC 8705 §3 `x5t#S256`
    * confirmation. Authenticating with a certificate implies it: the certificate is
    * already validated at the token endpoint, so handing back a token that anyone who
    * steals it can replay throws away the only property mutual TLS was bought for.
    * The registered flag therefore only decides the case the RFC separates §3 for — a
    * client authenticating by secret or `private_key_jwt` that still presents a certificate
    * purely to have its tokens bound.
    */
  def bindsAccessTokens: Boolean = mtlsAuth.nonEmpty || certificateBoundAccessTokens

  def isPublic: Boolean = !isConfidential
