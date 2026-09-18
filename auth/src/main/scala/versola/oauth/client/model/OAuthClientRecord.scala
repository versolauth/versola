package versola.oauth.client.model

import versola.util.{JsonWebKeySet, Secret}
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
    /** RFC 8705 §2.1 mutual-TLS client authentication; `None` when the client
      * authenticates with a secret. */
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
      * Mutually exclusive with [[mtlsAuth]], which registration enforces. */
    jwks: Option[JsonWebKeySet],
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
