package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** Roles on central's admin API.
  *
  * A role is what a user is actually assigned; the permissions it carries are copied into the
  * access token as `roles` and resolved by the edge on every proxied request. An accidental
  * widening here becomes a privilege escalation for everyone holding the role, so these
  * concentrate on the add/remove semantics of the permission patch.
  */
object RoleApiSpec extends CentralApiSpec:

  private val path = "/configuration/roles"
  private val permissions = "/configuration/permissions"

  private def find(central: CentralApi, roleId: String, tenantId: String): Task[Option[Json.Obj]] =
    central.get(path, "tenantId" -> tenantId)
      .flatMap(_.items("roles"))
      .map(_.find(_.str("id").contains(roleId)))

  /** The role as central reports it once the write has landed. `expect` is what the test is
    * waiting for: without it a read taken straight after an update can still answer the
    * version from before.
    */
  private def read(
      central: CentralApi,
      roleId: String,
      tenantId: String = Fixtures.defaultTenant,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, roleId, tenantId))(_.exists(expect))

  /** Waits for the role to be gone, for the tests that assert an absence. */
  private def gone(central: CentralApi, roleId: String, tenantId: String = Fixtures.defaultTenant): Task[Option[Json.Obj]] =
    eventually(find(central, roleId, tenantId))(_.isEmpty)

  private def cleanup(central: CentralApi, roleId: String, tenantId: String = Fixtures.defaultTenant): UIO[Unit] =
    central.delete(path, "tenantId" -> tenantId, "roleId" -> roleId).ignore.unit

  /** Declares a permission for the duration of the test body: a role granting one that does
    * not exist proves nothing about the grant.
    */
  private def withPermission[A](central: CentralApi)(use: String => Task[A]): Task[A] =
    for
      permission <- CentralApi.permission("probe")
      _ <- central.post(permissions, Fixtures.permission(permission))
      result <- use(permission).ensuring(
        central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> permission).ignore,
      )
    yield result

  def spec = suite("Central API: roles")(
    test("a created role is listed with its description") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        created <- central.post(path, Fixtures.role(roleId, description = "Support agent"))
        record <- read(central, roleId)
        _ <- cleanup(central, roleId)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(record.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Support agent"))
    },
    test("a created role is active") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId))
        record <- read(central, roleId)
        _ <- cleanup(central, roleId)
      yield assertTrue(record.flatMap(_.bool("active")).contains(true))
        .label("a role that is created inactive could be assigned yet grant nothing, with no hint why")
    },
    test("a role is stored with the permissions it grants") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        outcome <- withPermission(central) { permission =>
          central.post(path, Fixtures.role(roleId, permissions = Set(permission)))
            *> read(central, roleId).map(_ -> permission)
        }
        (record, permission) = outcome
        _ <- cleanup(central, roleId)
      yield assertTrue(record.map(_.strings("permissions")).contains(Set(permission)))
    },
    test("a role can be created with no permissions") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        created <- central.post(path, Fixtures.role(roleId))
        record <- read(central, roleId)
        _ <- cleanup(central, roleId)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(record.map(_.strings("permissions")).contains(Set.empty[String]))
          .label("an empty role is a legitimate placeholder while an access model is being built")
    },
    test("a role granting several permissions stores all of them") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        first <- CentralApi.permission("probe")
        second <- CentralApi.permission("probe")
        _ <- central.post(permissions, Fixtures.permission(first))
        _ <- central.post(permissions, Fixtures.permission(second))
        _ <- central.post(path, Fixtures.role(roleId, permissions = Set(first, second)))
        record <- read(central, roleId)
        _ <- cleanup(central, roleId)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> first)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> second)
      yield assertTrue(record.map(_.strings("permissions")).contains(Set(first, second)))
    },
    test("a role id outside the documented alphabet is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.role("Bad Role"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Role ID"))
    },
    test("a role without a description is refused") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        rejected <- central.post(
          path,
          Json.Obj("tenantId" -> Json.Str(Fixtures.defaultTenant), "id" -> Json.Str(roleId)),
        )
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "admin")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update grants an additional permission") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        existing <- CentralApi.permission("probe")
        added <- CentralApi.permission("probe")
        _ <- central.post(permissions, Fixtures.permission(existing))
        _ <- central.post(permissions, Fixtures.permission(added))
        _ <- central.post(path, Fixtures.role(roleId, permissions = Set(existing)))
        updated <- central.put(path, Fixtures.roleUpdate(roleId, permissions = Fixtures.patch(add = Set(added))))
        record <- read(central, roleId, expect = _.strings("permissions") == Set(existing, added))
        _ <- cleanup(central, roleId)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> existing)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> added)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.map(_.strings("permissions")).contains(Set(existing, added)))
          .label("granting one permission must not drop the ones the role already carried")
    },
    test("an update revokes a permission") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        kept <- CentralApi.permission("probe")
        revoked <- CentralApi.permission("probe")
        _ <- central.post(permissions, Fixtures.permission(kept))
        _ <- central.post(permissions, Fixtures.permission(revoked))
        _ <- central.post(path, Fixtures.role(roleId, permissions = Set(kept, revoked)))
        _ <- central.put(path, Fixtures.roleUpdate(roleId, permissions = Fixtures.patch(remove = Set(revoked))))
        record <- read(central, roleId, expect = _.strings("permissions") == Set(kept))
        _ <- cleanup(central, roleId)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> kept)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> revoked)
      yield assertTrue(record.map(_.strings("permissions")).contains(Set(kept)))
        .label("revocation is the security-critical direction: it has to take effect")
    },
    test("an update grants and revokes in one call") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        before <- CentralApi.permission("probe")
        after <- CentralApi.permission("probe")
        _ <- central.post(permissions, Fixtures.permission(before))
        _ <- central.post(permissions, Fixtures.permission(after))
        _ <- central.post(path, Fixtures.role(roleId, permissions = Set(before)))
        _ <- central.put(
          path,
          Fixtures.roleUpdate(roleId, permissions = Fixtures.patch(add = Set(after), remove = Set(before))),
        )
        record <- read(central, roleId, expect = _.strings("permissions") == Set(after))
        _ <- cleanup(central, roleId)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> before)
        _ <- central.delete(permissions, "tenantId" -> Fixtures.defaultTenant, "permission" -> after)
      yield assertTrue(record.map(_.strings("permissions")).contains(Set(after)))
        .label("swapping a permission in two calls would leave a window with both or neither")
    },
    test("revoking a permission the role never had is not an error") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId))
        updated <- central.put(
          path,
          Fixtures.roleUpdate(roleId, permissions = Fixtures.patch(remove = Set("probe:never_granted"))),
        )
        _ <- cleanup(central, roleId)
      yield assertTrue(updated.status == Status.NoContent)
        .label("a repeated desired-state apply repeats revocations; each has to be idempotent")
    },
    test("an update patches the role description") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId, description = "Support agent"))
        _ <- central.put(path, Fixtures.roleUpdate(roleId, description = Fixtures.patchText(add = Map("ru" -> "Агент"))))
        record <- read(central, roleId, expect = _.obj("description").exists(_.has("ru")))
        _ <- cleanup(central, roleId)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).contains("Support agent")) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Агент"))
    },
    test("an update deletes a translation of the role description") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId, description = "Support agent"))
        _ <- central.put(
          path,
          Fixtures.roleUpdate(
            roleId,
            description = Fixtures.patchText(add = Map("ru" -> "Агент"), delete = Set("en")),
          ),
        )
        record <- read(central, roleId, expect = _.obj("description").exists(!_.has("en")))
        _ <- cleanup(central, roleId)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).isEmpty) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Агент"))
    },
    test("an update that only renames the role leaves its permissions alone") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        outcome <- withPermission(central) { permission =>
          central.post(path, Fixtures.role(roleId, permissions = Set(permission)))
            *> central.put(path, Fixtures.roleUpdate(roleId, description = Fixtures.patchText(add = Map("ru" -> "А"))))
            *> read(central, roleId, expect = _.obj("description").exists(_.has("ru"))).map(_ -> permission)
        }
        (record, permission) = outcome
        _ <- cleanup(central, roleId)
      yield assertTrue(record.map(_.strings("permissions")).contains(Set(permission)))
        .label("editing a label must not silently change who can do what")
    },
    test("an update of an unknown role does not create one") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        updated <- central.put(path, Fixtures.roleUpdate(roleId, description = Fixtures.patchText(Map("en" -> "x"))))
        record <- gone(central, roleId)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.isEmpty)
          .label("a role that appears from an edit would be an unreviewed grant")
    },
    test("an update without the required patch members is refused") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        rejected <- central.put(
          path,
          Json.Obj("tenantId" -> Json.Str(Fixtures.defaultTenant), "id" -> Json.Str(roleId)),
        )
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("the listing is scoped to one tenant and demands to be told which") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
    },
    test("a role is not listed under a tenant it does not belong to") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.role(roleId, tenantId = tenantId))
        own <- read(central, roleId, tenantId)
        other <- gone(central, roleId)
        _ <- cleanup(central, roleId, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(own.nonEmpty) &&
        assertTrue(other.isEmpty)
          .label("a role name is tenant-local; leaking one across tenants would leak its grants too")
    },
    test("the same role id can exist in two tenants at once") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        inDefault <- central.post(path, Fixtures.role(roleId, description = "Default's admin"))
        inOther <- central.post(path, Fixtures.role(roleId, tenantId = tenantId, description = "Other's admin"))
        defaultRecord <- read(central, roleId, expect = _.obj("description").exists(_.str("en").contains("Default's admin")))
        otherRecord <- read(central, roleId, tenantId, expect = _.obj("description").exists(_.str("en").contains("Other's admin")))
        _ <- cleanup(central, roleId)
        _ <- cleanup(central, roleId, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(inDefault.status == Status.Created && inOther.status == Status.Created) &&
        assertTrue(defaultRecord.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Default's admin")) &&
        assertTrue(otherRecord.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Other's admin"))
    },
    test("limit caps the number of roles returned") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId))
        listed <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "limit" -> "1").flatMap(_.items("roles"))
        _ <- cleanup(central, roleId)
      yield assertTrue(listed.size == 1)
    },
    test("a deleted role stops being listed") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId))
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "roleId" -> roleId)
        record <- gone(central, roleId)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting an unknown role is not an error") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-absent")
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "roleId" -> roleId)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("deleting without a roleId is refused") {
      for
        central <- api
        rejected <- central.delete(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("roleId"))
    },
    test("a role id can be reused after the role holding it is deleted") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.role(roleId, description = "First"))
        _ <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "roleId" -> roleId)
        again <- central.post(path, Fixtures.role(roleId, description = "Second"))
        record <- read(central, roleId, expect = _.obj("description").exists(_.str("en").contains("Second")))
        _ <- cleanup(central, roleId)
      yield assertTrue(again.status == Status.Created) &&
        assertTrue(record.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Second")) &&
        assertTrue(record.map(_.strings("permissions")).contains(Set.empty[String]))
          .label("a recreated role must not inherit the grants of the one it replaces")
    },
    test("an anonymous caller cannot list roles") {
      for
        central <- api
        listed <- central.anonymous.get(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
    test("an anonymous caller cannot create a role") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        rejected <- central.anonymous.post(path, Fixtures.role(roleId))
        record <- gone(central, roleId)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.isEmpty)
    },
    test("a caller presenting the wrong secret cannot grant a permission to a role") {
      for
        central <- api
        roleId <- CentralApi.id("e2e-role")
        outcome <- withPermission(central) { permission =>
          central.post(path, Fixtures.role(roleId)) *>
            central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
              .put(path, Fixtures.roleUpdate(roleId, permissions = Fixtures.patch(add = Set(permission))))
              .zip(read(central, roleId))
        }
        (rejected, record) = outcome
        _ <- cleanup(central, roleId)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.map(_.strings("permissions")).contains(Set.empty[String]))
          .label("everyone holding the role would gain the permission, so this is the escalation path to close")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
