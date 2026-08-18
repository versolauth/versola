package versola.central.configuration.clients

import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{RedirectUri, Secret}
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
) derives Schema, CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential

  def hasPermission(permission: Permission): Boolean = permissions.contains(permission)
