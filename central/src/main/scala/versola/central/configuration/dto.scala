package versola.central.configuration

import versola.central.configuration.clients.{ClientId, PresetId, ResponseType}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.{ResourceEndpointId, ResourceId}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.scopes.{Claim, ClaimRecord, ScopeToken}
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
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
    allowRules: AclRuleTree,
    denyRules: AclRuleTree,
    injectHeaders: Map[String, String],
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
    id: ResourceId,
    resource: ResourceUri,
    endpoints: Vector[ResourceEndpointResponse],
) derives Schema, JsonCodec

case class ResourceEndpointResponse(
    id: ResourceEndpointId,
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allowRules: AclRuleTree,
    denyRules: AclRuleTree,
    injectHeaders: Map[String, String],
) derives Schema, JsonCodec

case class GetAllResourcesResponse(
    resources: Vector[ResourceResponse],
) derives Schema, JsonCodec

case class PermissionRule(
    subject: String,
    operator: String,
    value: Json,
    pattern: Option[String] = None,
) derives Schema, JsonCodec

case class AclRuleTree(
    kind: AclRuleTree.Kind,
    rule: Option[PermissionRule],
    children: Option[Vector[AclRuleTree]],
) derives CanEqual, Schema, JsonCodec

object AclRuleTree:
  val emptyAny: AclRuleTree = any()

  enum Kind:
    case rule, any, all

  object Kind:
    given JsonCodec[Kind] = JsonCodec.string.transform(Kind.valueOf, _.toString)
    given Schema[Kind] = Schema.primitive[String].transformOrFail(
      string => Try(Kind.valueOf(string)).toEither.left.map(_ => s"Invalid ACL rule kind: $string"),
      kind => Right(kind.toString),
    )

  def rule(permissionRule: PermissionRule): AclRuleTree =
    AclRuleTree(kind = Kind.rule, rule = Some(permissionRule), children = None)

  def all(children: Vector[AclRuleTree] = Vector.empty): AclRuleTree =
    AclRuleTree(kind = Kind.all, children = Some(children), rule = None)

  def any(children: Vector[AclRuleTree] = Vector.empty): AclRuleTree =
    AclRuleTree(kind = Kind.any, children = Some(children), rule = None)

case class PermissionEndpointAcl(
    method: String,
    path: String,
    fetchUserInfo: Boolean,
    allowRules: AclRuleTree,
    denyRules: AclRuleTree,
    injectHeaders: Map[String, String],
) derives Schema, JsonCodec

case class PermissionAcl(
    endpoints: Vector[PermissionEndpointAcl],
) derives Schema, JsonCodec

object PermissionAcl:
  val empty: PermissionAcl = PermissionAcl(Vector.empty)
  given Equal[PermissionAcl] = Equal.make(_ == _)

case class CreatePermissionRequest(
    tenantId: Option[TenantId],
    permission: Permission,
    description: Map[String, String],
    endpointIds: Set[ResourceEndpointId],
) derives Schema, JsonCodec

case class UpdatePermissionRequest(
    tenantId: Option[TenantId],
    permission: Permission,
    description: PatchDescription,
    endpointIds: Option[Set[ResourceEndpointId]],
) derives Schema, JsonCodec

case class CreateResourceRequest(
    tenantId: TenantId,
    resource: ResourceUri,
    endpoints: Vector[CreateResourceEndpointRequest],
) derives Schema, JsonCodec

case class UpdateResourceRequest(
    id: ResourceId,
    resource: Option[ResourceUri],
    deleteEndpoints: Set[ResourceEndpointId],
    createEndpoints: Vector[CreateResourceEndpointRequest],
) derives Schema, JsonCodec

case class CreateResourceResponse(
    id: ResourceId,
) derives Schema, JsonCodec

case class CreateResourceEndpointRequest(
    id: ResourceEndpointId,
    path: String,
    method: String,
    fetchUserInfo: Boolean,
    allowRules: AclRuleTree,
    denyRules: AclRuleTree,
    injectHeaders: Map[String, String],
) derives Schema, JsonCodec

case class UpdateResourceEndpointRequest(
    tenantId: TenantId,
    id: ResourceEndpointId,
    method: Option[String],
    path: Option[String],
    fetchUserInfo: Option[Boolean],
    allowRules: Option[AclRuleTree],
    denyRules: Option[AclRuleTree],
    injectHeaders: Option[Map[String, String]],
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

case class TenantResponse(
    id: TenantId,
    description: String,
    edgeId: Option[String],
) derives Schema, JsonCodec

case class GetAllTenantsResponse(
    tenants: Vector[TenantResponse],
) derives Schema, JsonCodec

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
    clientName: String,
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    permissions: Set[Permission],
    secretRotation: Boolean,
) derives Schema, JsonCodec

case class GetAllClientsResponse(
    clients: List[OAuthClientResponse],
) derives Schema, JsonCodec

case class CreateClientRequest(
    tenantId: TenantId,
    id: ClientId,
    clientName: String,
    redirectUris: Set[RedirectUri],
    allowedScopes: Set[ScopeToken],
    audience: List[ClientId],
    permissions: Set[Permission],
    accessTokenTtl: Int,
) derives Schema, JsonCodec

case class CreateClientResponse(
    secret: String,
) derives Schema, JsonEncoder

case class RotateSecretResponse(
    secret: String,
) derives Schema, JsonEncoder

case class UpdateClientRequest(
    tenantId: TenantId,
    clientId: ClientId,
    clientName: Option[String],
    redirectUris: PatchClientRedirectUris,
    scope: PatchClientScope,
    permissions: PatchPermissions,
    accessTokenTtl: Option[Long],
) derives Schema, JsonCodec

case class AuthorizationPresetInput(
    id: PresetId,
    description: String,
    redirectUri: RedirectUri,
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
) derives Schema, JsonCodec

case class SaveAuthorizationPresetsRequest(
    tenantId: TenantId,
    clientId: ClientId,
    presets: List[AuthorizationPresetInput],
) derives Schema, JsonCodec

case class AuthorizationPresetResponse(
    id: String,
    clientId: ClientId,
    description: String,
    redirectUri: RedirectUri,
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
) derives Schema, JsonCodec

case class GetClientPresetsResponse(
    presets: Vector[AuthorizationPresetResponse],
) derives Schema, JsonCodec

case class AuthorizationPresetSyncResponse(
    id: PresetId,
    clientId: ClientId,
    description: String,
    redirectUri: RedirectUri,
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
) derives Schema, JsonCodec

case class GetAuthorizationPresetsSyncResponse(
    presets: Vector[AuthorizationPresetSyncResponse],
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
    clientName: String,
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    externalAudience: List[ClientId],
    secret: Option[String],
    previousSecret: Option[String],
    accessTokenTtl: Duration,
) derives JsonCodec, Schema

case class GetOAuthClientsSyncResponse(
    clients: Vector[SyncOAuthClientRecord],
    pepper: String,
) derives JsonCodec, Schema