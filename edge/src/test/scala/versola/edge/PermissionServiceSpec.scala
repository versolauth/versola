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

  private val rolesMap: Map[RoleId, Set[PermissionId]] = Map(
    viewerRole -> Set(readPerm),
    editorRole -> Set(readPerm, writePerm),
    adminRole -> Set(readPerm, writePerm, adminPerm),
  )

  private val permissionsMap: Map[PermissionId, Set[ResourceEndpointId]] = Map(
    readPerm -> Set(listUsersEndpoint),
    writePerm -> Set(createUserEndpoint),
    adminPerm -> Set(deleteUserEndpoint),
  )

  private def buildService(
      roles: Map[RoleId, Set[PermissionId]] = rolesMap,
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
        for endpoints <- service.getAllowedEndpointsForRoles(List("viewer"))
        yield assertTrue(endpoints == Set(listUsersEndpoint))
      },
      test("merges and dedupes endpoints across multiple roles") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(List("viewer", "editor"))
        yield assertTrue(endpoints == Set(listUsersEndpoint, createUserEndpoint))
      },
      test("returns empty set when role list is empty") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(Nil)
        yield assertTrue(endpoints.isEmpty)
      },
      test("ignores unknown roles silently") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(List("ghost", "viewer"))
        yield assertTrue(endpoints == Set(listUsersEndpoint))
      },
      test("returns empty set when role grants permissions absent from permissionsCache") {
        val service = buildService(permissions = Map.empty)
        for endpoints <- service.getAllowedEndpointsForRoles(List("admin"))
        yield assertTrue(endpoints.isEmpty)
      },
      test("returns full endpoint set for admin role") {
        val service = buildService()
        for endpoints <- service.getAllowedEndpointsForRoles(List("admin"))
        yield assertTrue(endpoints == Set(listUsersEndpoint, createUserEndpoint, deleteUserEndpoint))
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
        val client = OAuthClient(id = serviceClient, secret = Secret(Array.fill(8)(1.toByte)))
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
  )
end PermissionServiceSpec
