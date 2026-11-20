package versola.central.configuration.tenants

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.details.{AuthorizationDetailType, AuthorizationDetailTypeRecord, AuthorizationDetailTypeRepository}
import versola.central.configuration.permissions.{Permission, PermissionRecord, PermissionRepository}
import versola.central.configuration.roles.{RoleId, RoleRecord, RoleRepository}
import versola.central.configuration.scopes.{Claim, ClaimRecord, OAuthScopeRepository, ScopeRecord, ScopeToken}
import versola.central.configuration.{CreateClaim, PatchClaim, PatchDescription, PatchPermissions, PatchScope}
import versola.util.DatabaseSpecBase
import zio.ZIO
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The same identifier in two tenants is two records, and a write or a delete in one never reaches the other.
  *
  * Every route that takes a `tenantId` ends in one of these repository operations, so this is where a missing `tenant_id` in a
  * `WHERE` would show up: a role, permission, scope or authorization detail type of tenant B that changes, loses a claim, or
  * disappears because tenant A's was updated or deleted. Each case creates an identical record in both tenants, applies every
  * write and delete the repository offers to A's, and compares B's with what it was.
  *
  * The tenants `tenant-a` and `tenant-b` are created by the runner; the lists a repository serves to the cache (`getAll`) are
  * checked as well, since that is what the rest of central reads.
  */
trait TenantIsolationSpec extends DatabaseSpecBase[TenantIsolationSpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val a = TenantId("tenant-a")
  private val b = TenantId("tenant-b")

  override def testCases(env: TenantIsolationSpec.Env) =
    List(
      suite("roles")(
        test("updating, deactivating, upserting and deleting tenant A's role leaves tenant B's as it was") {
          val roleId = RoleId("support")
          val read = Permission("users:read")
          val write = Permission("users:write")
          for
            _ <- ZIO.foreachDiscard(List(a, b))(t => env.roles.createRole(t, roleId, Map("en" -> "Support"), List(read), active = true))
            before <- env.roles.findRole(b, roleId)
            _ <- env.roles.updateRole(
              a,
              roleId,
              PatchDescription(add = Map("ru" -> "Поддержка"), delete = Set.empty),
              PatchPermissions(add = Set(write), remove = Set(read)),
              active = Some(false),
            )
            _ <- env.roles.upsertRole(a, roleId, Map("en" -> "Changed"), List(write))
            _ <- env.roles.markRoleInactive(a, roleId)
            afterWrites <- env.roles.findRole(b, roleId)
            _ <- env.roles.deleteRole(a, roleId)
            afterDelete <- env.roles.findRole(b, roleId)
            deletedA <- env.roles.findRole(a, roleId)
            all <- env.roles.getAll
          yield assertTrue(
            before == Some(RoleRecord(id = roleId, tenantId = b, description = Map("en" -> "Support"), permissions = Set(read), active = true)),
            afterWrites == before,
            afterDelete == before,
            deletedA.isEmpty,
            all.filter(_.id == roleId).map(_.tenantId) == Vector(b),
          )
        },
      ),
      suite("permissions")(
        test("updating, upserting and deleting tenant A's permission leaves tenant B's as it was") {
          val permission = Permission("users:read")
          for
            _ <- ZIO.foreachDiscard(List(a, b))(t => env.permissions.createPermission(t, permission, Map("en" -> "Read users"), Set.empty))
            before <- env.permissions.findPermission(b, permission)
            _ <- env.permissions.updatePermission(a, permission, PatchDescription(add = Map("en" -> "Changed"), delete = Set.empty), None)
            _ <- env.permissions.upsertPermission(a, permission, Map("en" -> "Upserted"), Set.empty)
            afterWrites <- env.permissions.findPermission(b, permission)
            _ <- env.permissions.deletePermission(a, permission)
            afterDelete <- env.permissions.findPermission(b, permission)
            deletedA <- env.permissions.findPermission(a, permission)
            all <- env.permissions.getAll
          yield assertTrue(
            before == Some(PermissionRecord(tenantId = b, id = permission, description = Map("en" -> "Read users"), endpointIds = Set.empty)),
            afterWrites == before,
            afterDelete == before,
            deletedA.isEmpty,
            all.filter(_.id == permission).map(_.tenantId) == Vector(b),
          )
        },
      ),
      suite("scopes")(
        test("updating and deleting tenant A's scope, its claims included, leaves tenant B's as it was") {
          val scope = ScopeToken("profile")
          val name = Claim("name")
          val email = Claim("email")
          val claims = List(CreateClaim(name, Map("en" -> "Name")), CreateClaim(email, Map("en" -> "Email")))
          for
            _ <- ZIO.foreachDiscard(List(a, b))(t => env.scopes.createScope(t, scope, Map("en" -> "Profile"), claims))
            before <- env.scopes.findScope(b, scope)
            _ <- env.scopes.updateScope(
              a,
              scope,
              PatchScope(
                add = Nil,
                update = List(PatchClaim(name, PatchDescription(add = Map("en" -> "Changed"), delete = Set.empty))),
                delete = Set(email),
                description = PatchDescription(add = Map("en" -> "Changed"), delete = Set.empty),
              ),
            )
            afterWrites <- env.scopes.findScope(b, scope)
            _ <- env.scopes.deleteScope(a, scope)
            afterDelete <- env.scopes.findScope(b, scope)
            deletedA <- env.scopes.findScope(a, scope)
            all <- env.scopes.getAll
          yield assertTrue(
            before.exists(_.claims.map(_.id).toSet == Set(name, email)),
            afterWrites == before,
            afterDelete == before,
            deletedA.isEmpty,
            all.filter(_.id == scope).map(_.tenantId) == Vector(b),
          )
        },
      ),
      suite("authorization detail types")(
        test("updating and deleting tenant A's type leaves tenant B's as it was") {
          val detailType = AuthorizationDetailType("payment_initiation")
          val schema = """{"type":"object"}""".fromJson[Json.Obj].toOption.get
          val changed = """{"type":"object","required":["type"]}""".fromJson[Json.Obj].toOption.get
          for
            _ <- ZIO.foreachDiscard(List(a, b))(t => env.detailTypes.createType(t, detailType, Map("en" -> "Payment"), schema))
            before <- env.detailTypes.findType(b, detailType)
            _ <- env.detailTypes.updateType(a, detailType, Map("en" -> "Changed"), changed)
            afterWrites <- env.detailTypes.findType(b, detailType)
            _ <- env.detailTypes.deleteType(a, detailType)
            afterDelete <- env.detailTypes.findType(b, detailType)
            deletedA <- env.detailTypes.findType(a, detailType)
            all <- env.detailTypes.getAll
          yield assertTrue(
            before == Some(AuthorizationDetailTypeRecord(tenantId = b, `type` = detailType, description = Map("en" -> "Payment"), schema = schema)),
            afterWrites == before,
            afterDelete == before,
            deletedA.isEmpty,
            all.filter(_.`type` == detailType).map(_.tenantId) == Vector(b),
          )
        },
      ),
    )

object TenantIsolationSpec:
  case class Env(
      roles: RoleRepository,
      permissions: PermissionRepository,
      scopes: OAuthScopeRepository,
      detailTypes: AuthorizationDetailTypeRepository,
  )
