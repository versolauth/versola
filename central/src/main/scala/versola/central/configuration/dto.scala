package versola.central.configuration

import versola.central.configuration.clients.{AuthFlow, AuthMethod, ClientId, ConsentFlow, MutualTlsAuth, PresetId, RegistrationFlow, ResponseType}
import versola.central.configuration.details.AuthorizationDetailType
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.{ResourceEndpointId, ResourceId}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.scopes.{Claim, ClaimRecord, ScopeToken}
import versola.central.configuration.tenants.TenantId
import versola.util.{Dpop, JsonWebKeySet, Patch, PrivateClientCertificate, PrivateJsonWebKey, RedirectUri}
import zio.http.{Scheme, URL}
import zio.json.ast.Json
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}
import zio.prelude.Equal
import zio.schema.*
import zio.{Duration, NonEmptyChunk}

import scala.util.Try

case class CreateClaim(
    id: Claim,
    description: Map[String, String],
) derives JsonCodec, Schema:
  def asRecord: ClaimRecord = ClaimRecord(id, description)

case class CreatePermission(
    permission: Permission,
    description: Map[String, String],
    endpointIds: Set[ResourceEndpointId],
) derives JsonCodec, Schema

case class CreateResource(
    resource: ResourceUri,
) derives JsonCodec, Schema

case class CreateResourceEndpoint(
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    inject: Vector[InjectRule],
) derives JsonCodec, Schema

enum InjectTarget derives JsonCodec:
  case header, query, body

object InjectTarget:
  given Schema[InjectTarget] = Schema.primitive[String].transformOrFail(
    string => Try(InjectTarget.valueOf(string)).toEither.left.map(_ => s"Invalid inject target: $string"),
    target => Right(target.toString),
  )

case class InjectRule(
    target: InjectTarget,
    name: String,
    expression: String,
) derives JsonCodec, Schema

case class CreateScope(
    id: ScopeToken,
    description: Map[String, String],
    claims: List[CreateClaim],
) derives JsonCodec, Schema

case class PatchDescription(
    add: Map[String, String],
    delete: Set[String],
) derives Schema, JsonCodec:
  def patch(existing: Map[String, String]): Map[String, String] =
    (existing -- delete) ++ add

case class PatchClientRedirectUris(
    add: Set[RedirectUri],
    remove: Set[RedirectUri],
) derives Schema, JsonCodec

case class PatchClientScope(
    add: Set[ScopeToken],
    remove: Set[ScopeToken],
) derives Schema, JsonCodec

case class PatchPermissions(
    add: Set[Permission],
    remove: Set[Permission],
) derives JsonCodec, Schema

case class PatchAudience(
    add: Set[ClientId],
    remove: Set[ClientId],
) derives Schema, JsonCodec:
  /** Order-preserving and idempotent: a client already in the audience is not duplicated,
    * so concurrent writers adding themselves cannot drop each other the way submitting a
    * whole list does. */
  def patch(existing: List[ClientId]): List[ClientId] =
    existing.filterNot(remove.contains) ++ add.filterNot(existing.contains)

object PatchAudience:
  val empty: PatchAudience = PatchAudience(Set.empty, Set.empty)

case class PatchScope(
    add: List[CreateClaim],
    update: List[PatchClaim],
    delete: Set[Claim],
    description: PatchDescription,
) derives JsonCodec, Schema

case class PatchClaim(
    id: Claim,
    description: PatchDescription,
) derives JsonCodec, Schema

case class PermissionResponse(
    permission: Permission,
    description: Map[String, String],
    endpointIds: Set[ResourceEndpointId],
) derives Schema, JsonCodec

case class LinkedResourceResponse(
)

case class GetAllPermissionsResponse(
    permissions: Vector[PermissionResponse],
) derives Schema, JsonCodec

case class ResourceResponse(
    resourceId: ResourceId,
    resource: ResourceUri,
    audience: List[ClientId],
    endpoints: Vector[ResourceEndpointResponse],
    internal: Boolean,
    secretRotation: Boolean,
) derives Schema, JsonCodec

case class ResourceEndpointResponse(
    id: ResourceEndpointId,
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    inject: Vector[InjectRule],
    stepUpCondition: Option[String],
    stepUpAcr: Option[String],
    maxAge: Option[Int],
) derives Schema, JsonCodec

case class GetAllResourcesResponse(
    resources: Vector[ResourceResponse],
) derives Schema, JsonCodec

case class CreatePermissionRequest(
    tenantId: TenantId,
    permission: Permission,
    description: Map[String, String],
    endpointIds: Set[ResourceEndpointId],
) derives Schema, JsonCodec

case class UpdatePermissionRequest(
    tenantId: TenantId,
    permission: Permission,
    description: PatchDescription,
    endpointIds: Option[Set[ResourceEndpointId]],
) derives Schema, JsonCodec

case class CreateResourceRequest(
    tenantId: TenantId,
    resourceId: ResourceId,
    resource: ResourceUri,
    audience: List[ClientId],
    endpoints: Vector[CreateResourceEndpointRequest],
    internal: Boolean,
) derives Schema, JsonCodec

case class UpdateResourceRequest(
    resourceId: ResourceId,
    resource: Option[ResourceUri],
    audience: PatchAudience,
    deleteEndpoints: Set[ResourceEndpointId],
    createEndpoints: Vector[CreateResourceEndpointRequest],
) derives Schema, JsonCodec

case class CreateResourceResponse(
    resourceId: ResourceId,
    secret: Option[String],
) derives Schema, JsonCodec

case class RotateResourceSecretResponse(
    secret: String,
) derives Schema, JsonEncoder

case class CreateResourceEndpointRequest(
    id: ResourceEndpointId,
    path: String,
    method: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    inject: Vector[InjectRule],
    stepUpCondition: Option[String],
    stepUpAcr: Option[String],
    maxAge: Option[Int],
) derives Schema, JsonCodec

case class CreateRoleRequest(
    tenantId: TenantId,
    id: RoleId,
    description: Map[String, String],
    permissions: Set[Permission],
) derives Schema, JsonCodec

case class UpdateRoleRequest(
    tenantId: TenantId,
    id: RoleId,
    description: PatchDescription,
    permissions: PatchPermissions,
) derives Schema, JsonCodec

case class RoleResponse(
    id: RoleId,
    description: Map[String, String],
    permissions: Set[Permission],
    active: Boolean,
) derives JsonCodec, Schema

case class GetAllRolesResponse(
    roles: Vector[RoleResponse],
) derives Schema, JsonCodec

case class ClaimResponse(
    claim: Claim,
    description: Map[String, String],
) derives Schema, JsonCodec

case class ScopeWithClaimsResponse(
    scope: ScopeToken,
    description: Map[String, String],
    claims: Vector[ClaimResponse],
) derives Schema, JsonCodec

case class GetAllScopesResponse(scopes: Vector[ScopeWithClaimsResponse]) derives Schema, JsonCodec

case class ClaimInput(
    id: Claim,
    description: Map[String, String],
) derives Schema, JsonCodec

case class CreateScopeRequest(
    tenantId: TenantId,
    id: ScopeToken,
    description: Map[String, String],
    claims: List[CreateClaim],
) derives Schema, JsonCodec

case class UpdateScopeRequest(
    tenantId: TenantId,
    id: ScopeToken,
    patch: PatchScope,
) derives Schema, JsonCodec

case class AuthorizationDetailTypeResponse(
    `type`: AuthorizationDetailType,
    description: Map[String, String],
    schema: Json.Obj,
) derives Schema, JsonCodec

case class GetAllAuthorizationDetailTypesResponse(
    types: Vector[AuthorizationDetailTypeResponse],
) derives Schema, JsonCodec

case class CreateAuthorizationDetailTypeRequest(
    tenantId: TenantId,
    `type`: AuthorizationDetailType,
    description: Map[String, String],
    schema: Json.Obj,
) derives Schema, JsonCodec

case class UpdateAuthorizationDetailTypeRequest(
    tenantId: TenantId,
    `type`: AuthorizationDetailType,
    description: Map[String, String],
    schema: Json.Obj,
) derives Schema, JsonCodec

case class TenantResponse(
    id: TenantId,
    description: String,
    edgeId: Option[String],
) derives Schema, JsonCodec

case class GetAllTenantsResponse(
    tenants: Vector[TenantResponse],
) derives Schema, JsonCodec

/** Deliberately takes no `submissionLimits` field -- every tenant is seeded with
  * `SubmissionLimits.recommended` (see `TenantService.createTenant`) and that's never a
  * caller-supplied choice, so there's no per-request rate-limit configuration to validate
  * or fall back on here.
  */
case class CreateTenantRequest(
    id: TenantId,
    description: String,
    edgeId: Option[String],
) derives Schema, JsonCodec

case class UpdateTenantRequest(
    id: TenantId,
    description: String,
    edgeId: Option[String],
) derives Schema, JsonCodec

case class OAuthClientResponse(
    id: ClientId,
    clientName: Map[String, String],
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    permissions: Set[Permission],
    secretRotation: Boolean,
    /** How the client authenticates, as it registered. Also says whether there is a secret
      * to rotate: only `client_secret` has one. */
    authMethod: AuthMethod,
    accessTokenTtl: Long,
    refreshTokenTtl: Long,
    theme: String,
    authFlow: Option[AuthFlow],
    registrationFlow: Option[RegistrationFlow],
    otpTemplateId: String,
    frontChannelLogoutUri: Option[String],
    frontChannelLogoutSessionRequired: Boolean,
    backChannelLogoutUri: Option[String],
    logoUri: Option[String],
    policyUri: Option[String],
    tosUri: Option[String],
    consentFlow: Option[ConsentFlowDto],
    dpopBoundAccessTokens: Boolean,
    /** RFC 9449 §5.1: the signing algorithms a DPoP proof from this client may use, narrowing
      * what the metadata document advertises. Empty means no narrowing. */
    dpopSigningAlgs: Set[Dpop.Algorithm],
    /** The modulus length an RSA DPoP proof key from this client must reach; `None` leaves the
      * RFC 7518 §3.3 floor, which a registration can only raise. */
    dpopMinRsaKeySize: Option[Int],
    /** RFC 8705 §2.1 mutual-TLS client authentication; `None` when the client
      * authenticates with a secret. */
    mtlsAuth: Option[MutualTlsAuth],
    /** RFC 8705 §3.4: bind this client's access tokens to the certificate it presents. */
    certificateBoundAccessTokens: Boolean,
    /** RFC 7523 §2.2 `private_key_jwt`: the public keys the client signs its client
      * assertions with; `None` when it does not use the method. */
    jwks: Option[JsonWebKeySet],
    /** RFC 9101 §10.5: the client states its authorization request in a request object it
      * signed. */
    requireSignedRequestObject: Boolean,
    /** RFC 9126 §6.2: the client pushes its authorization request to `/par` first. */
    requirePushedAuthorizationRequests: Boolean,
) derives Schema, JsonCodec

case class ConsentFlowDto(
    allowPartial: Boolean,
    rememberDuration: Option[Long],
) derives Schema, JsonCodec:
  def toDomain: ConsentFlow =
    ConsentFlow(
      allowPartial = allowPartial,
      rememberDuration = rememberDuration.map(Duration.fromSeconds),
    )

object ConsentFlowDto:
  def fromDomain(flow: ConsentFlow): ConsentFlowDto =
    ConsentFlowDto(
      allowPartial = flow.allowPartial,
      rememberDuration = flow.rememberDuration.map(_.toSeconds),
    )

case class GetAllClientsResponse(
    clients: List[OAuthClientResponse],
) derives Schema, JsonCodec

case class CreateClientRequest(
    tenantId: TenantId,
    id: ClientId,
    clientName: Map[String, String],
    redirectUris: Set[RedirectUri],
    allowedScopes: Set[ScopeToken],
    permissions: Set[Permission],
    accessTokenTtl: Int,
    refreshTokenTtl: Option[Int],
    theme: String,
    authFlow: Option[AuthFlow],
    registrationFlow: Option[RegistrationFlow],
    otpTemplateId: String,
    frontChannelLogoutUri: Option[String],
    frontChannelLogoutSessionRequired: Boolean,
    backChannelLogoutUri: Option[String],
    logoUri: Option[String],
    policyUri: Option[String],
    tosUri: Option[String],
    consentFlow: Option[ConsentFlowDto],
    /** RFC 9449 §5.2: whether every token issued to this client is bound to a proof key,
      * rather than DPoP staying opt-in per request. */
    dpopBoundAccessTokens: Boolean,
    /** RFC 9449 §5.1: the signing algorithms a DPoP proof from this client may use, narrowing
      * what the metadata document advertises. Empty means no narrowing. */
    dpopSigningAlgs: Set[Dpop.Algorithm],
    /** The modulus length an RSA DPoP proof key from this client must reach; `None` leaves the
      * RFC 7518 §3.3 floor, which a registration can only raise. */
    dpopMinRsaKeySize: Option[Int],
    /** How the client will authenticate. Decides whether a secret is issued at all, and is
      * held to agree with [[mtlsAuth]] and [[jwks]]: a method names the credential, and a
      * credential registered for a method that does not read it is one nothing would ever
      * check.
      */
    authMethod: AuthMethod,
    /** RFC 8705 §2.1 mutual-TLS client authentication; `None` when the client
      * authenticates with a secret. */
    mtlsAuth: Option[MutualTlsAuth],
    /** RFC 8705 §3.4: bind this client's access tokens to the certificate it presents. */
    certificateBoundAccessTokens: Boolean,
    /** RFC 7523 §2.2 `private_key_jwt`: the public keys the client signs its client
      * assertions with; `None` when it does not use the method. */
    jwks: Option[JsonWebKeySet],
    /** RFC 9101 §10.5: whether this client states its authorization request in a signed
      * request object, rather than a plain parameter set staying acceptable from it. */
    requireSignedRequestObject: Boolean,
    /** RFC 9126 §6.2: whether this client must push its authorization request to `/par`
      * first, rather than `/authorize` staying reachable directly. */
    requirePushedAuthorizationRequests: Boolean,
    /** The private key an edge fronting this client signs with; `None` when no edge does, which
      * is every client registered before edges could authenticate by key. Must be the private
      * half of a key [[jwks]] publishes — registration refuses a pair that cannot verify. */
    edgeSigningKey: Option[PrivateJsonWebKey],
    /** The certificate an edge fronting this client presents at the TLS handshake, private key
      * included, as a single PEM. `None` when no edge does. Requires [[mtlsAuth]] — a
      * certificate is looked for only where the registration says one authenticates. */
    edgeClientCertificate: Option[PrivateClientCertificate],
) derives Schema, JsonCodec

/** `secret` is absent for a native client - there is none to hand back. */
case class CreateClientResponse(
    secret: Option[String],
) derives Schema, JsonEncoder

case class RotateSecretResponse(
    secret: String,
) derives Schema, JsonEncoder

case class UpdateClientRequest(
    clientId: ClientId,
    clientName: Option[Map[String, String]],
    redirectUris: PatchClientRedirectUris,
    scope: PatchClientScope,
    permissions: PatchPermissions,
    accessTokenTtl: Option[Long],
    refreshTokenTtl: Option[Long],
    theme: Option[String],
    authFlow: Option[Patch[AuthFlow]],
    registrationFlow: Option[Patch[RegistrationFlow]],
    otpTemplateId: Option[String],
    frontChannelLogoutUri: Option[Patch[String]],
    frontChannelLogoutSessionRequired: Option[Boolean],
    backChannelLogoutUri: Option[Patch[String]],
    logoUri: Option[Patch[String]],
    policyUri: Option[Patch[String]],
    tosUri: Option[Patch[String]],
    consentFlow: Option[Patch[ConsentFlowDto]],
    dpopBoundAccessTokens: Option[Boolean],
    dpopSigningAlgs: Option[Set[Dpop.Algorithm]],
    dpopMinRsaKeySize: Option[Patch[Int]],
    /** Moving a client to another method is a change of credential, not of transport, so it
      * is validated against the resulting [[mtlsAuth]] and [[jwks]] exactly as a registration
      * is. Leaving `client_secret` drops the stored secret: a credential the client no longer
      * authenticates with is one nobody can be told has stopped working. */
    authMethod: Option[AuthMethod],
    mtlsAuth: Option[Patch[MutualTlsAuth]],
    certificateBoundAccessTokens: Option[Boolean],
    jwks: Option[Patch[JsonWebKeySet]],
    requireSignedRequestObject: Option[Boolean],
    requirePushedAuthorizationRequests: Option[Boolean],
    edgeSigningKey: Option[Patch[PrivateJsonWebKey]],
    edgeClientCertificate: Option[Patch[PrivateClientCertificate]],
) derives Schema, JsonCodec

case class AuthorizationPresetInput(
    id: PresetId,
    description: String,
    redirectUri: RedirectUri,
    postLoginRedirectUri: RedirectUri,
    postLogoutRedirectUri: Option[RedirectUri],
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
    cookieDomain: Option[String],
    cookiePath: Option[String],
) derives Schema, JsonCodec

case class SaveAuthorizationPresetsRequest(
    clientId: ClientId,
    presets: List[AuthorizationPresetInput],
) derives Schema, JsonCodec

case class AuthorizationPresetResponse(
    id: String,
    clientId: ClientId,
    description: String,
    redirectUri: RedirectUri,
    postLoginRedirectUri: RedirectUri,
    postLogoutRedirectUri: Option[RedirectUri],
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
    cookieDomain: Option[String],
    cookiePath: Option[String],
) derives Schema, JsonCodec

case class GetClientPresetsResponse(
    presets: Vector[AuthorizationPresetResponse],
) derives Schema, JsonCodec

case class AuthorizationPresetSyncResponse(
    id: PresetId,
    clientId: ClientId,
    description: String,
    redirectUri: RedirectUri,
    postLoginRedirectUri: RedirectUri,
    postLogoutRedirectUri: Option[RedirectUri],
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
    cookieDomain: Option[String],
    cookiePath: Option[String],
) derives Schema, JsonCodec

case class GetAuthorizationPresetsSyncResponse(
    presets: Vector[AuthorizationPresetSyncResponse],
) derives Schema, JsonCodec

case class ResourceEndpointSyncResponse(
    id: ResourceEndpointId,
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allow: Option[String],
    inject: Vector[InjectRule],
    stepUpCondition: Option[String],
    stepUpAcr: Option[String],
    maxAge: Option[Int],
) derives Schema, JsonCodec

case class ResourceSyncResponse(
    resourceId: ResourceId,
    tenantId: TenantId,
    resource: ResourceUri,
    endpoints: Vector[ResourceEndpointSyncResponse],
    secret: Option[String],
) derives Schema, JsonCodec

case class GetResourcesSyncResponse(
    resources: Vector[ResourceSyncResponse],
) derives Schema, JsonCodec

case class ResourceRegistryEntry(
    resourceId: ResourceId,
    tenantId: TenantId,
    resource: ResourceUri,
    audience: List[ClientId],
    internal: Boolean,
) derives Schema, JsonCodec

case class GetResourcesRegistryResponse(
    resources: Vector[ResourceRegistryEntry],
    /** Base64Url-encoded current secret of the "auth" resource, encrypted the same way as
      * [[ResourceSyncResponse.secret]] (AES-256 with the central secret key, since auth has
      * no edge keypair). It lets auth authenticate calls to its own account settings API
      * without a separately configured static secret. No other resource secret is exposed
      * here. */
    authResourceSecret: Option[String],
    /** The secret being rotated out, sent alongside the current one so auth accepts either
      * while a rotation is in flight. Edge keeps using the previous secret until it is
      * explicitly removed ([[ResourceSyncResponse.secret]]), and edge and auth refresh their
      * caches independently: without this, the window between edge picking up the new secret
      * and auth doing the same rejects every call. Mirrors central's own
      * `ResourceService.verifySecret`, which accepts both for the same reason. */
    authResourcePreviousSecret: Option[String],
) derives Schema, JsonCodec

case class AuthorizationDetailTypeSyncResponse(
    tenantId: TenantId,
    `type`: AuthorizationDetailType,
    schema: Json.Obj,
) derives Schema, JsonCodec

case class GetAuthorizationDetailTypesSyncResponse(
    types: Vector[AuthorizationDetailTypeSyncResponse],
) derives Schema, JsonCodec

type ResourceUri = ResourceUri.Type

object ResourceUri:
  opaque type Type <: String = String

  inline def apply(uri: String): ResourceUri = uri

  def parse(uri: String): Either[String, ResourceUri] =
    URL.decode(uri) match
      case Left(_) =>
        Left(s"Invalid URI format: $uri")
      case Right(url) if !url.isAbsolute =>
        Left("Resource URI must be absolute")
      case _ if uri.regionMatches(true, 0, "resource://", 0, "resource://".length) =>
        Left("Resource URI scheme resource:// is reserved")
      case Right(url) if url.path.nonEmpty =>
        Left("Resource URI path must be empty")
      case Right(url) if url.queryParams.nonEmpty =>
        Left("Resource URI query must be empty")
      case Right(url) if url.fragment.isDefined =>
        Left("Resource URI fragment must be empty")
      case Right(_) =>
        Right(ResourceUri(uri))

  given Equal[Type] = Equal.make(_ == _)

  given Schema[Type] = Schema.primitive[String]
    .transformOrFail(parse, Right(_))

  given JsonEncoder[Type] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Type] = JsonDecoder.string.mapOrFail(parse)

case class SyncOAuthClientRecord(
    id: String,
    tenantId: String,
    clientName: Map[String, String],
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    secret: Option[String],
    previousSecret: Option[String],
    accessTokenTtl: Duration,
    refreshTokenTtl: Duration,
    permissions: Set[Permission],
    theme: String,
    authFlow: Option[AuthFlow],
    registrationFlow: Option[RegistrationFlow],
    otpTemplateId: String,
    frontChannelLogoutUri: Option[String],
    frontChannelLogoutSessionRequired: Boolean,
    backChannelLogoutUri: Option[String],
    logoUri: Option[String],
    policyUri: Option[String],
    tosUri: Option[String],
    consentFlow: Option[ConsentFlow],
    dpopBoundAccessTokens: Boolean,
    /** RFC 9449 §5.1: the signing algorithms a DPoP proof from this client may use, narrowing
      * what the metadata document advertises. Empty means no narrowing. */
    dpopSigningAlgs: Set[Dpop.Algorithm],
    /** The modulus length an RSA DPoP proof key from this client must reach; `None` leaves the
      * RFC 7518 §3.3 floor, which a registration can only raise. */
    dpopMinRsaKeySize: Option[Int],
    /** How the client authenticates, as it registered. `auth` reads this rather than working
      * it out from the fields below, so that the two can never disagree about which
      * credential is in force. */
    authMethod: AuthMethod,
    /** RFC 8705 §2.1 mutual-TLS client authentication; `None` when the client
      * authenticates with a secret. */
    mtlsAuth: Option[MutualTlsAuth],
    /** RFC 8705 §3.4: bind this client's access tokens to the certificate it presents. */
    certificateBoundAccessTokens: Boolean,
    /** RFC 7523 §2.2 `private_key_jwt`: the public keys the client signs its client
      * assertions with; `None` when it does not use the method. */
    jwks: Option[JsonWebKeySet],
    /** RFC 9101 §10.5: the client states its authorization request in a request object it
      * signed. Always written, so an auth node reading a central that predates the field is
      * the only reader that has to supply its own default. */
    requireSignedRequestObject: Boolean,
    /** RFC 9126 §6.2: the client pushes its authorization request to `/par` first. */
    requirePushedAuthorizationRequests: Boolean,
    /** The private JWK an edge fronting this client signs with, encrypted in transit exactly
      * as `secret` is — to the requesting edge's registered RSA public key. Absent for a
      * caller that is not an edge, which has no key to decrypt it with and no use for it. */
    edgeSigningKey: Option[String],
    /** The PEM certificate and key an edge fronting this client presents, encrypted in transit
      * on the same terms as `edgeSigningKey`. */
    edgeClientCertificate: Option[String],
) derives JsonCodec, Schema

case class GetOAuthClientsSyncResponse(
    clients: Vector[SyncOAuthClientRecord],
) derives JsonCodec, Schema

case class PermissionSyncResponse(
    tenantId: TenantId,
    id: Permission,
    endpointIds: Set[ResourceEndpointId],
) derives JsonCodec, Schema

case class GetPermissionsSyncResponse(
    permissions: Vector[PermissionSyncResponse],
) derives JsonCodec, Schema

case class RoleSyncResponse(
    tenantId: TenantId,
    id: RoleId,
    permissions: Set[Permission],
    active: Boolean,
) derives JsonCodec, Schema

case class GetRolesSyncResponse(
    roles: Vector[RoleSyncResponse],
) derives JsonCodec, Schema