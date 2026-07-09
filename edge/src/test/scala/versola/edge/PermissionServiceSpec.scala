package versola.edge

import versola.edge.model.*
import versola.util.{ReloadingCache, Secret}
import zio.*
import zio.test.*

object PermissionServiceSpec extends ZIOSpecDefault:

  private val readPerm = PermissionId("read")
  private val writePerm = PermissionId("write")
  private val adminPerm = PermissionId("admin")

  private val listUsersEndpoint = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000001"))
  private val createUserEndpoint = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000002"))
  private val deleteUserEndpoint = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000003"))

  private val viewerRole = RoleId("viewer")
  private val editorRole = RoleId("editor")
  private val adminRole = RoleId("admin")

  private val serviceClient = ClientId("svc-1")
  private val unknownClient = ClientId("svc-missing")

  private val defaultTenant = TenantId.default

  private val rolesMap: Map[(TenantId, RoleId), Set[PermissionId]] = Map(
    (defaultTenant, viewerRole) -> Set(readPerm),
    (defaultTenant, editorRole) -> Set(readPerm, writePerm),
    (defaultTenant, adminRole) -> Set(readPerm, writePerm, adminPerm),
  )

  private val permissionsMap: Map[PermissionId, Set[ResourceEndpointId]] = Map(
    readPerm -> Set(listUsersEndpoint),
    writePerm -> Set(createUserEndpoint),
    adminPerm -> Set(deleteUserEndpoint),
  )

  private def buildService(
      roles: Map[(TenantId, RoleId), Set[PermissionId]] = rolesMap,
      permissions: Map[PermissionId, Set[ResourceEndpointId]] = permissionsMap,
      clients: Map[ClientId, OAuthClient] = Map.empty,
  ): PermissionService =
    val rolesCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(roles)))
    val permissionsCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(permissions)))
    val clientsCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(clients)))
    PermissionService.Impl(rolesCache, permissionsCache, clientsCache)

  def spec = suite("PermissionService")(
    suite("getAllowedEndpointsForRoles")(
      test("returns endpoints composed from a single role's permissions") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(Map(defaultTenant -> List(viewerRole)))
        yield assertTrue(endpoints == Set(listUsersEndpoint))
      },
      test("merges and dedupes endpoints across multiple roles") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(Map(defaultTenant -> List(viewerRole, editorRole)))
        yield assertTrue(endpoints == Set(listUsersEndpoint, createUserEndpoint))
      },
      test("returns empty set when role map is empty") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(Map.empty)
        yield assertTrue(endpoints.isEmpty)
      },
      test("ignores unknown roles silently") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(Map(defaultTenant -> List(RoleId("ghost"), viewerRole)))
        yield assertTrue(endpoints == Set(listUsersEndpoint))
      },
      test("returns empty set when role grants permissions absent from permissionsCache") {
        val service = buildService(permissions = Map.empty)
        for endpoints <- service.getAllowedEndpointsForRoles(Map(defaultTenant -> List(adminRole)))
        yield assertTrue(endpoints.isEmpty)
      },
      test("returns full endpoint set for admin role") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(Map(defaultTenant -> List(adminRole)))
        yield assertTrue(endpoints == Set(listUsersEndpoint, createUserEndpoint, deleteUserEndpoint))
      },
      test("merges endpoints across multiple tenants") {
        val tenantA = TenantId("tenant-a")
        val multiTenantRoles: Map[(TenantId, RoleId), Set[PermissionId]] = Map(
          (defaultTenant, viewerRole) -> Set(readPerm),
          (tenantA, editorRole)       -> Set(writePerm),
        )
        val service = buildService(roles = multiTenantRoles)
        for endpoints <- service.getAllowedEndpointsForRoles(
          Map(defaultTenant -> List(viewerRole), tenantA -> List(editorRole)),
        )
        yield assertTrue(endpoints == Set(listUsersEndpoint, createUserEndpoint))
      },
      test("deduplicates endpoints when multiple tenants grant the same permission") {
        val tenantA = TenantId("tenant-a")
        val multiTenantRoles: Map[(TenantId, RoleId), Set[PermissionId]] = Map(
          (defaultTenant, viewerRole) -> Set(readPerm),
          (tenantA, viewerRole)       -> Set(readPerm),
        )
        val service = buildService(roles = multiTenantRoles)
        for endpoints <- service.getAllowedEndpointsForRoles(
          Map(defaultTenant -> List(viewerRole), tenantA -> List(viewerRole)),
        )
        yield assertTrue(endpoints == Set(listUsersEndpoint))
      },
    ),
    suite("getAllowedEndpointsForClient")(
      test("returns endpoints composed from the client's permissions") {
        val client = OAuthClient(id = serviceClient, secret = Secret(Array.fill(8)(1.toByte)), permissions = Set(writePerm))
        val service = buildService(clients = Map(serviceClient -> client))
        for endpoints <- service.getAllowedEndpointsForClient(serviceClient)
        yield assertTrue(endpoints == Set(createUserEndpoint))
      },
      test("returns empty set when client is missing from the cache") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForClient(unknownClient)
        yield assertTrue(endpoints.isEmpty)
      },
      test("returns empty set when client has no permissions") {
        val client = OAuthClient(id = serviceClient, secret = Secret(Array.fill(8)(1.toByte)), permissions = Set.empty)
        val service = buildService(clients = Map(serviceClient -> client))
        for endpoints <- service.getAllowedEndpointsForClient(serviceClient)
        yield assertTrue(endpoints.isEmpty)
      },
      test("ignores client permissions that map to no endpoints") {
        val client = OAuthClient(id = serviceClient, secret = Secret(Array.fill(8)(1.toByte)), permissions = Set(PermissionId("unmapped")))
        val service = buildService(clients = Map(serviceClient -> client))
        for endpoints <- service.getAllowedEndpointsForClient(serviceClient)
        yield assertTrue(endpoints.isEmpty)
      },
    ),
    suite("getPermissionsForRoles")(
      test("returns permissions whose endpoint IDs intersect with the provided set") {
        val service = buildService()
        for permissions <- service.getPermissionsForRoles(
          Map(defaultTenant -> List(adminRole)),
          Set(listUsersEndpoint),
        )
        yield assertTrue(permissions == Set(readPerm))
      },
      test("returns multiple permissions when several intersect") {
        val service = buildService()
        for permissions <- service.getPermissionsForRoles(
          Map(defaultTenant -> List(adminRole)),
          Set(listUsersEndpoint, createUserEndpoint),
        )
        yield assertTrue(permissions == Set(readPerm, writePerm))
      },
      test("returns empty set when no permission endpoint IDs intersect") {
        val service = buildService()
        val unrelatedEndpoint = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000099"))
        for permissions <- service.getPermissionsForRoles(
          Map(defaultTenant -> List(adminRole)),
          Set(unrelatedEndpoint),
        )
        yield assertTrue(permissions.isEmpty)
      },
      test("returns empty set when role map is empty") {
        val service = buildService()
        for permissions <- service.getPermissionsForRoles(Map.empty, Set(listUsersEndpoint))
        yield assertTrue(permissions.isEmpty)
      },
      test("returns empty set when endpointIds is empty") {
        val service = buildService()
        for permissions <- service.getPermissionsForRoles(
          Map(defaultTenant -> List(adminRole)),
          Set.empty,
        )
        yield assertTrue(permissions.isEmpty)
      },
      test("merges permissions across multiple tenants filtered by endpoints") {
        val tenantA = TenantId("tenant-a")
        val multiTenantRoles: Map[(TenantId, RoleId), Set[PermissionId]] = Map(
          (defaultTenant, viewerRole) -> Set(readPerm),
          (tenantA, editorRole)       -> Set(writePerm),
        )
        val service = buildService(roles = multiTenantRoles)
        for permissions <- service.getPermissionsForRoles(
          Map(defaultTenant -> List(viewerRole), tenantA -> List(editorRole)),
          Set(listUsersEndpoint, createUserEndpoint),
        )
        yield assertTrue(permissions == Set(readPerm, writePerm))
      },
    ),
  )
end PermissionServiceSpec
