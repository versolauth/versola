package versola.central.configuration.clients

import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.{JsonWebKeySet, Patch}
import zio.Duration
import zio.http.URL

/** What an update may change about a registered client, in the repository's own types: a
  * `None` leaves the stored value alone, and the set-valued members carry what to add and
  * what to remove rather than a replacement.
  *
  * One value rather than one parameter per field, because a client has more registrable
  * settings than a parameter list stays readable -- or, past twenty-two, than the stub
  * library the repository is faked with can record a call of.
  */
case class OAuthClientPatch(
    clientName: Option[Map[String, String]],
    redirectUris: PatchClientRedirectUris,
    scope: PatchClientScope,
    permissions: PatchPermissions,
    accessTokenTtl: Option[Duration],
    refreshTokenTtl: Option[Duration],
    theme: Option[String],
    authFlow: Option[Patch[AuthFlow]],
    registrationFlow: Option[Patch[RegistrationFlow]],
    otpTemplateId: Option[String],
    frontChannelLogoutUri: Option[Patch[URL]],
    frontChannelLogoutSessionRequired: Option[Boolean],
    backChannelLogoutUri: Option[Patch[URL]],
    logoUri: Option[Patch[String]],
    policyUri: Option[Patch[String]],
    tosUri: Option[Patch[String]],
    consentFlow: Option[Patch[ConsentFlow]],
    dpopBoundAccessTokens: Option[Boolean],
    mtlsAuth: Option[Patch[MutualTlsAuth]],
    certificateBoundAccessTokens: Option[Boolean],
    jwks: Option[Patch[JsonWebKeySet]],
    requireSignedRequestObject: Option[Boolean],
    requirePushedAuthorizationRequests: Option[Boolean],
)

object OAuthClientPatch:
  val empty: OAuthClientPatch = OAuthClientPatch(
    clientName = None,
    redirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
    scope = PatchClientScope(Set.empty, Set.empty),
    permissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl = None,
    refreshTokenTtl = None,
    theme = None,
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = None,
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = None,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = None,
    mtlsAuth = None,
    certificateBoundAccessTokens = None,
    jwks = None,
    requireSignedRequestObject = None,
    requirePushedAuthorizationRequests = None,
  )
