package versola.central.configuration.roles

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.challenges.{
  ChallengeSettingsRecord,
  ChallengeSettingsService,
  EmailRegistration,
  PasskeySettings,
  SecurityProfile,
  SubmissionLimits,
}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.configuration.{CreateRoleRequest, PatchDescription, PatchPermissions, UpdateRoleRequest}
import versola.util.ReloadingCache
import zio.*
import zio.prelude.EqualOps
import zio.test.*

object RoleServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val adminRoleId = RoleId("admin")
  private val operatorRoleId = RoleId("operator")
  private val readPermission = Permission("users:read")
  private val writePermission = Permission("users:write")

  private val adminRole = RoleRecord(adminRoleId, tenantId, Map("en" -> "Admin"), Set(readPermission), active = true)
  private val operatorRole = RoleRecord(operatorRoleId, tenantId, Map("en" -> "Operator"), Set(writePermission), active = false)
  private val otherTenantRole = RoleRecord(RoleId("viewer"), otherTenantId, Map("en" -> "Viewer"), Set(readPermission), active = true)

  private val createRequest = CreateRoleRequest(
    tenantId = tenantId,
    id = adminRoleId,
    description = Map("en" -> "Admin"),
    permissions = Set(readPermission, writePermission),
    active = false,
  )

  private val updateRequest = UpdateRoleRequest(
    tenantId = tenantId,
    id = adminRoleId,
    description = PatchDescription(add = Map("ru" -> "Админ"), delete = Set.empty),
    permissions = PatchPermissions(add = Set(writePermission), remove = Set(readPermission)),
    active = Some(true),
  )

  private val settings = ChallengeSettingsRecord(
    tenantId = tenantId,
    phoneRegions = List.empty,
    emailRegistration = EmailRegistration.Closed,
    defaultRoles = Set(adminRoleId),
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 60,
    passkeySettings = PasskeySettings("localhost", "Test", List("http://localhost"), "preferred"),
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 86400,
    sessionIdleTtlSeconds = None,
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Real-IP",
    acrVocabulary = None,
    postLogoutRedirectUris = List.empty,
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
    securityProfile = SecurityProfile.fapi2,
    logoLight = None,
    logoDark = None,
  )

  class Env(initial: Vector[RoleRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[RoleRepository]
    val tenantRepository = stub[TenantRepository]
    val challengeSettings = stub[ChallengeSettingsService]
    val service = RoleService.Impl(cache, repository, tenantRepository, challengeSettings)

  def spec = suite("RoleService")(
    test("getTenantRoles filters cache by tenant") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        result <- env.service.getTenantRoles(tenantId, offset = 0, limit = None)
      yield assertTrue(result === Vector(adminRole))
    },
    test("getTenantRoles applies pagination after filtering") {
      val env = new Env(Vector(adminRole, operatorRole, otherTenantRole))

      for
        result <- env.service.getTenantRoles(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result === Vector(operatorRole))
    },
    test("createRole delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.createRole.succeedsWith(())
        _ <- env.service.createRole(createRequest)
      yield assertTrue(
        env.repository.createRole.calls === List((tenantId, adminRoleId, createRequest.description, createRequest.permissions.toList, false)),
      )
    },
    test("updateRole delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updateRole.succeedsWith(())
        _ <- env.service.updateRole(updateRequest)
      yield assertTrue(
        env.repository.updateRole.calls == List((tenantId, adminRoleId, updateRequest.description, updateRequest.permissions, Some(true))),
      )
    },
    test("markRoleInactive delegates tenant and id to repository") {
      val env = new Env()

      for
        _ <- env.repository.markRoleInactive.succeedsWith(())
        _ <- env.service.markRoleInactive(tenantId, adminRoleId)
      yield assertTrue(env.repository.markRoleInactive.calls === List((tenantId, adminRoleId)))
    },
    test("deleteRole delegates tenant and id to repository") {
      val env = new Env()

      for
        _ <- env.challengeSettings.getSettings.succeedsWith(None)
        _ <- env.repository.deleteRole.succeedsWith(())
        _ <- env.service.deleteRole(tenantId, adminRoleId)
      yield assertTrue(env.repository.deleteRole.calls === List((tenantId, adminRoleId)))
    },
    test("deleteRole refuses a role that is one of the tenant's default roles") {
      val env = new Env()

      for
        _ <- env.challengeSettings.getSettings.succeedsWith(Some(settings))
        exit <- env.service.deleteRole(tenantId, adminRoleId).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[RoleService.RoleInUseError]),
        env.repository.deleteRole.calls.isEmpty,
      )
    },
    test("deleteRole deletes a role the tenant's settings do not name") {
      val env = new Env()

      for
        _ <- env.challengeSettings.getSettings.succeedsWith(Some(settings))
        _ <- env.repository.deleteRole.succeedsWith(())
        _ <- env.service.deleteRole(tenantId, operatorRoleId)
      yield assertTrue(env.repository.deleteRole.calls === List((tenantId, operatorRoleId)))
    },
    test("sync removes cached role on delete event") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        _ <- env.service.sync(SyncEvent.RolesUpdated(tenantId, adminRoleId, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached === Vector(otherTenantRole))
    },
    test("sync upserts fetched role for non-delete event") {
      val env = new Env(Vector(adminRole, otherTenantRole))
      val updatedRole = adminRole.copy(description = Map("en" -> "Updated admin"), permissions = Set(readPermission, writePermission))

      for
        _ <- env.repository.findRole.succeedsWith(Some(updatedRole))
        _ <- env.service.sync(SyncEvent.RolesUpdated(tenantId, adminRoleId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findRole.calls === List((tenantId, adminRoleId)),
        cached === Vector(updatedRole, otherTenantRole),
      )
    },
    test("sync removes cached role when record is missing on non-delete event") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        _ <- env.repository.findRole.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.RolesUpdated(tenantId, adminRoleId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findRole.calls === List((tenantId, adminRoleId)),
        cached === Vector(otherTenantRole),
      )
    },
    test("getPermissionsForRoles merges permissions from all matching roles") {
      val env = new Env(Vector(adminRole, operatorRole, otherTenantRole))

      for
        result <- env.service.getPermissionsForRoles(tenantId, Set(adminRoleId, operatorRoleId))
      yield assertTrue(result == Set(readPermission, writePermission))
    },
    test("getPermissionsForRoles excludes roles from other tenants") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        result <- env.service.getPermissionsForRoles(tenantId, Set(adminRoleId, otherTenantRole.id))
      yield assertTrue(result == Set(readPermission))
    },
    test("getPermissionsForRoles returns empty set when no role IDs match") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        result <- env.service.getPermissionsForRoles(tenantId, Set(RoleId("unknown")))
      yield assertTrue(result.isEmpty)
    },
    test("getPermissionsForRoles returns empty set for empty role IDs") {
      val env = new Env(Vector(adminRole))

      for
        result <- env.service.getPermissionsForRoles(tenantId, Set.empty)
      yield assertTrue(result.isEmpty)
    },
    test("getRolesForSync returns all cached roles when no edge is specified") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        result <- env.service.getRolesForSync(None)
      yield assertTrue(result === Vector(adminRole, otherTenantRole))
    },
    test("getRolesForSync filters roles to tenants assigned to the given edge") {
      val edgeId = EdgeId("edge-1")
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        _ <- env.tenantRepository.getAll.succeedsWith(
          Vector(
            TenantRecord(tenantId, "Tenant A", Some(edgeId)),
            TenantRecord(otherTenantId, "Tenant B", None),
          ),
        )
        result <- env.service.getRolesForSync(Some(edgeId))
      yield assertTrue(result === Vector(adminRole))
    },
    test("getRolesForSync excludes all roles when no tenant is assigned to the given edge") {
      val edgeId = EdgeId("edge-1")
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        _ <- env.tenantRepository.getAll.succeedsWith(
          Vector(
            TenantRecord(tenantId, "Tenant A", None),
            TenantRecord(otherTenantId, "Tenant B", None),
          ),
        )
        result <- env.service.getRolesForSync(Some(edgeId))
      yield assertTrue(result.isEmpty)
    },
  )
