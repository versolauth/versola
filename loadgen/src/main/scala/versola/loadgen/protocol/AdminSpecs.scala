package versola.loadgen.protocol

import zio.json.ast.Json

import java.util.UUID

// The typed admin requests [[AdminClient]] takes, one per central/edge admin operation a campaign
// needs (versola-loadgen-dev-spec.md §4). Field-for-field these are central's own create/update
// DTOs minus what a load campaign never varies: no localized descriptions (one English string),
// no tenant per call (a campaign provisions one tenant, named once on the client), and no
// secret-rotation or delete surface.
//
// `authFlow`/`registrationFlow` stay `Json`: they are the shared flow documents of §3.4, read
// verbatim out of `loadgen/src/main/resources/flows/` so that the emulator and the e2e suite
// cannot drift from central's schema independently. Re-typing that tree here would reintroduce
// exactly the second definition §3.4 exists to prevent.

/** @param publicClient registers a `native` client -- public, PKCE-only, never issued a secret
  *                     (design doc §2.2: the three mobile clients). `false` registers a `web`
  *                     client, which is confidential and gets one.
  */
case class ClientSpec(
    clientId: String,
    clientName: String,
    redirectUris: Set[String],
    allowedScopes: Set[String],
    accessTokenTtlSeconds: Int,
    refreshTokenTtlSeconds: Option[Int],
    publicClient: Boolean,
    authFlow: Json,
    registrationFlow: Option[Json],
    backChannelLogoutUri: Option[String],
)

/** One protected endpoint of a resource, relative to `/resources/{resourceId}` -- the part edge
  * appends to the resource's base URI, `{name}` path templates included.
  *
  * `id` is what a [[PermissionSpec]] grants, so it must be the same value on every `provision`
  * run; [[versola.loadgen.provision.CampaignBlueprint]] derives it from the endpoint's identity
  * rather than drawing a fresh UUID.
  *
  * @param allow           CEL access rule, evaluated per request (design doc §3: the writes carry
  *                        one so `checkRules` is on the measured path rather than a no-op)
  * @param stepUpCondition CEL predicate deciding whether `stepUpAcr` applies at all
  * @param stepUpAcr       space-separated ACR values, any one of which satisfies the endpoint
  * @param maxAgeSeconds   RFC 9470 freshness bound on `auth_time`
  */
case class ResourceEndpointSpec(
    id: UUID,
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    stepUpCondition: Option[String],
    stepUpAcr: Option[String],
    maxAgeSeconds: Option[Int],
)

/** @param resourceUri the resource's RFC 8707 identifier, which is also the base URI edge proxies
  *                    to -- central requires it to be absolute and path-less
  * @param audience    the clients allowed to obtain tokens for this resource
  */
case class ResourceSpec(
    resourceId: String,
    resourceUri: String,
    audience: List[String],
    endpoints: List[ResourceEndpointSpec],
)

case class PermissionSpec(
    permission: String,
    description: String,
    endpointIds: Set[UUID],
)

case class RoleSpec(
    roleId: String,
    description: String,
    permissions: Set[String],
)

/** A named, server-side authorization request edge's `/login/{presetId}` starts (§8.4).
  *
  * Carries no `acr_values`: [[EdgeClient.login]] passes one per call, and pinning it here would
  * make every web session start out already satisfying the L2 endpoints, removing the web half of
  * the step-up load the campaign exists to measure (design doc §2.3).
  */
case class AuthRequestPresetSpec(
    presetId: String,
    description: String,
    redirectUri: String,
    postLoginRedirectUri: String,
    postLogoutRedirectUri: Option[String],
    scope: Set[String],
    responseType: String,
    cookieDomain: Option[String],
    cookiePath: Option[String],
)

/** Central replaces a client's preset set outright, so this is the whole desired set for one
  * client, not an addition to it.
  */
case class AuthRequestPresetsSpec(
    clientId: String,
    presets: List[AuthRequestPresetSpec],
)

/** @param acrVocabulary which authentication factors each ACR value requires -- what makes
  *                      `acr_values` mean anything to auth, and therefore what makes a step-up
  *                      resolvable at all (§7.4)
  */
case class ChallengeSettingsSpec(
    allowedPrefixes: List[String],
    otpLength: Int,
    otpResendAfterSeconds: Int,
    passkeyRpId: String,
    passkeyRpName: String,
    passkeyOrigins: Set[String],
    passkeyUserVerification: String,
    ipHeader: String,
    acrVocabulary: Map[String, List[String]],
)
