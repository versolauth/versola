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
    clientName: String,
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
) derives CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential
