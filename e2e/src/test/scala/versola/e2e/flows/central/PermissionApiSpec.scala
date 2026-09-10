package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** Permissions on central's admin API.
  *
  * A permission is the link between a role and the resource endpoints it unlocks. The edge
  * denies by default, so an endpoint reachable through a permission is reachable only because
  * that permission names it — which makes the endpoint set, and who may edit it, the whole
  * substance of these tests.
  */
object PermissionApiSpec extends CentralApiSpec:

  private val path = "/configuration/permissions"
  private val resources = "/configuration/resources"

  private def find(central: CentralApi, permission: String, tenantId: String): Task[Option[Json.Obj]] =
    central.get(path, "tenantId" -> tenantId)
      .flatMap(_.items("permissions"))
      .map(_.find(_.str("permission").contains(permission)))

  /** The permission as central reports it once the write has landed. `expect` is what the test
    * is waiting for: without it a read taken straight after an update can still answer the
    * version from before.
    */
  private def read(
      central: CentralApi,
      permission: String,
      tenantId: String = Fixtures.defaultTenant,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, permission, tenantId))(_.exists(expect))

  /** Waits for the permission to be gone, for the tests that assert an absence. */
  private def gone(
      central: CentralApi,
      permission: String,
      tenantId: String = Fixtures.defaultTenant,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, permission, tenantId))(_.isEmpty)

  private def cleanup(central: CentralApi, permission: String, tenantId: String = Fixtures.defaultTenant): UIO[Unit] =
    central.delete(path, "tenantId" -> tenantId, "permission" -> permission).ignore.unit

  /** Registers a resource with one endpoint and answers that endpoint's id, so a permission
    * test can grant something that actually exists.
    */
  private def withEndpoint[A](central: CentralApi)(use: String => Task[A]): Task[A] =
    for
      resourceId <- CentralApi.id("e2e-perm-resource")
      endpointId <- CentralApi.uuid.map(_.toString)
      _ <- central.post(
        resources,
        Fixtures.resource(resourceId, s"https://$resourceId.test", endpoints = List(Fixtures.endpoint(endpointId))),
      )
      result <- use(endpointId).ensuring(central.delete(resources, "resourceId" -> resourceId).ignore)
    yield result

  def spec = suite("Central API: permissions")(
    test("a created permission is listed with its description") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        created <- central.post(path, Fixtures.permission(permission, description = "Read orders"))
        record <- read(central, permission)
        _ <- cleanup(central, permission)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(record.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Read orders"))
    },
    test("a permission is stored with the endpoints it unlocks") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        record <- withEndpoint(central) { endpointId =>
          central.post(path, Fixtures.permission(permission, endpointIds = Set(endpointId)))
            *> read(central, permission).map(_ -> endpointId)
        }
        (stored, endpointId) = record
        _ <- cleanup(central, permission)
      yield assertTrue(stored.map(_.strings("endpointIds")).contains(Set(endpointId)))
        .label("the edge grants an endpoint only if a permission names its id, so the link must be exact")
    },
    test("a permission can be created with no endpoints yet") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        created <- central.post(path, Fixtures.permission(permission))
        record <- read(central, permission)
        _ <- cleanup(central, permission)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(record.map(_.strings("endpointIds")).contains(Set.empty[String]))
          .label("a permission is usually declared before the endpoints it will cover exist")
    },
    test("a permission unlocking several endpoints stores all of them") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        resourceId <- CentralApi.id("e2e-perm-resource")
        first <- CentralApi.uuid.map(_.toString)
        second <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          resources,
          Fixtures.resource(
            resourceId,
            s"https://$resourceId.test",
            endpoints = List(Fixtures.endpoint(first, path = "/items"), Fixtures.endpoint(second, path = "/orders")),
          ),
        )
        _ <- central.post(path, Fixtures.permission(permission, endpointIds = Set(first, second)))
        record <- read(central, permission)
        _ <- cleanup(central, permission)
        _ <- central.delete(resources, "resourceId" -> resourceId)
      yield assertTrue(record.map(_.strings("endpointIds")).contains(Set(first, second)))
    },
    test("a segmented permission name is accepted") {
      for
        central <- api
        base <- CentralApi.permission("probe")
        permission = s"$base:orders.read_all"
        created <- central.post(path, Fixtures.permission(permission))
        record <- read(central, permission)
        _ <- cleanup(central, permission)
      yield assertTrue(created.status == Status.Created) && assertTrue(record.nonEmpty)
        .label("`resource:action` and dotted namespaces are the documented naming convention")
    },
    test("a permission name outside the documented alphabet is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.permission("Bad Permission"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Permission"))
    },
    test("a permission name whose segment starts with a digit is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.permission("probe:1read"))
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("every segment must start with a letter, so a name cannot be mistaken for an index")
    },
    test("a permission without a description is refused") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        rejected <- central.post(
          path,
          Json.Obj("tenantId" -> Json.Str(Fixtures.defaultTenant), "permission" -> Json.Str(permission)),
        )
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "orders:read")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update adds a translation to the description") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        _ <- central.post(path, Fixtures.permission(permission, description = "Read orders"))
        updated <- central.put(
          path,
          Fixtures.permissionUpdate(permission, description = Fixtures.patchText(add = Map("ru" -> "Чтение"))),
        )
        record <- read(central, permission, expect = _.obj("description").exists(_.has("ru")))
        _ <- cleanup(central, permission)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(description.flatMap(_.str("en")).contains("Read orders")) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Чтение"))
    },
    test("an update removes a translation from the description") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        _ <- central.post(path, Fixtures.permission(permission, description = "Read orders"))
        _ <- central.put(
          path,
          Fixtures.permissionUpdate(
            permission,
            description = Fixtures.patchText(add = Map("ru" -> "Чтение"), delete = Set("en")),
          ),
        )
        record <- read(central, permission, expect = _.obj("description").exists(!_.has("en")))
        _ <- cleanup(central, permission)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).isEmpty) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Чтение"))
    },
    test("an update replaces the endpoint set outright") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        resourceId <- CentralApi.id("e2e-perm-resource")
        before <- CentralApi.uuid.map(_.toString)
        after <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          resources,
          Fixtures.resource(
            resourceId,
            s"https://$resourceId.test",
            endpoints = List(Fixtures.endpoint(before, path = "/items"), Fixtures.endpoint(after, path = "/orders")),
          ),
        )
        _ <- central.post(path, Fixtures.permission(permission, endpointIds = Set(before)))
        _ <- central.put(path, Fixtures.permissionUpdate(permission, endpointIds = Some(Set(after))))
        record <- read(central, permission, expect = _.strings("endpointIds") == Set(after))
        _ <- cleanup(central, permission)
        _ <- central.delete(resources, "resourceId" -> resourceId)
      yield assertTrue(record.map(_.strings("endpointIds")).contains(Set(after)))
        .label("this is a desired-state write: an endpoint left in the set would stay granted")
    },
    test("an update can revoke every endpoint a permission unlocked") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        record <- withEndpoint(central) { endpointId =>
          central.post(path, Fixtures.permission(permission, endpointIds = Set(endpointId)))
            *> central.put(path, Fixtures.permissionUpdate(permission, endpointIds = Some(Set.empty)))
            *> read(central, permission, expect = _.strings("endpointIds").isEmpty)
        }
        _ <- cleanup(central, permission)
      yield assertTrue(record.map(_.strings("endpointIds")).contains(Set.empty[String]))
        .label("emptying the set is how an operator suspends a permission without deleting it")
    },
    test("an update that names no endpoint set leaves the current one alone") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        outcome <- withEndpoint(central) { endpointId =>
          central.post(path, Fixtures.permission(permission, endpointIds = Set(endpointId)))
            *> central.put(
              path,
              Fixtures.permissionUpdate(permission, description = Fixtures.patchText(add = Map("ru" -> "Ч"))),
            )
            *> read(central, permission, expect = _.obj("description").exists(_.has("ru"))).map(_ -> endpointId)
        }
        (record, endpointId) = outcome
        _ <- cleanup(central, permission)
      yield assertTrue(record.map(_.strings("endpointIds")).contains(Set(endpointId)))
        .label("editing a label must not silently revoke access")
    },
    test("an update of an unknown permission does not create one") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        updated <- central.put(
          path,
          Fixtures.permissionUpdate(permission, description = Fixtures.patchText(add = Map("en" -> "Conjured"))),
        )
        record <- gone(central, permission)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.isEmpty)
          .label("a permission that appears from an edit would be a grant nobody reviewed")
    },
    test("the listing is scoped to one tenant and demands to be told which") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
    },
    test("a permission is not listed under a tenant it does not belong to") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        permission <- CentralApi.permission("probe")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.permission(permission, tenantId = tenantId))
        own <- read(central, permission, tenantId)
        other <- gone(central, permission)
        _ <- cleanup(central, permission, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(own.nonEmpty) && assertTrue(other.isEmpty)
    },
    test("limit caps the number of permissions returned") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        _ <- central.post(path, Fixtures.permission(permission))
        listed <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "limit" -> "1")
          .flatMap(_.items("permissions"))
        _ <- cleanup(central, permission)
      yield assertTrue(listed.size == 1)
    },
    test("a deleted permission stops being listed") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        _ <- central.post(path, Fixtures.permission(permission))
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "permission" -> permission)
        record <- gone(central, permission)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting a permission does not delete the roles that granted it") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        roleId <- CentralApi.id("e2e-role")
        _ <- central.post(path, Fixtures.permission(permission))
        _ <- central.post("/configuration/roles", Fixtures.role(roleId, permissions = Set(permission)))
        _ <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "permission" -> permission)
        roles <- eventually(central.get("/configuration/roles", "tenantId" -> Fixtures.defaultTenant).flatMap(_.items("roles")))(
          _.exists(_.str("id").contains(roleId)),
        )
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> roleId)
        role = roles.find(_.str("id").contains(roleId))
      yield assertTrue(role.nonEmpty) &&
        assertTrue(role.flatMap(_.bool("active")).contains(true))
          .label("retiring one permission must not take down every role and user assignment built on it")
    },
    test("deleting an unknown permission is not an error") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "permission" -> permission)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("deleting without naming the permission is refused") {
      for
        central <- api
        rejected <- central.delete(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("permission"))
    },
    test("an anonymous caller cannot list permissions") {
      for
        central <- api
        listed <- central.anonymous.get(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(listed.status == Status.Unauthorized)
        .label("the listing maps every permission to the endpoints it opens; that is an attack map")
    },
    test("an anonymous caller cannot create a permission") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        rejected <- central.anonymous.post(path, Fixtures.permission(permission))
        record <- gone(central, permission)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.isEmpty)
    },
    test("a caller presenting the wrong secret cannot widen a permission") {
      for
        central <- api
        permission <- CentralApi.permission("probe")
        outcome <- withEndpoint(central) { endpointId =>
          central.post(path, Fixtures.permission(permission)) *>
            central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
              .put(path, Fixtures.permissionUpdate(permission, endpointIds = Some(Set(endpointId))))
              .zip(read(central, permission))
        }
        (rejected, record) = outcome
        _ <- cleanup(central, permission)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.map(_.strings("endpointIds")).contains(Set.empty[String]))
          .label("privilege escalation through an unauthenticated update is the worst case for this endpoint")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
