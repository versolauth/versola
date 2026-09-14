package versola.oauth.client.model

import versola.util.Secret
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
    /** RFC 8705 §3.4: bind this client's access tokens to the certificate it presents. */
    certificateBoundAccessTokens: Boolean,
) derives CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential
