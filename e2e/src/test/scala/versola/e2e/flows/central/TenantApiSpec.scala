package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Tenant CRUD on central's admin API.
  *
  * The tenant is the root of every other configuration entity, so these cover the whole
  * lifecycle — created, listed, updated, deleted — plus what the endpoint does with an id it
  * has never seen and one that does not match its documented pattern.
  */
object TenantApiSpec extends CentralApiSpec:

  private val path = "/configuration/tenants"

  private def find(central: CentralApi): Task[Chunk[Json.Obj]] =
    central.get(path).flatMap(_.items("tenants"))

  /** The tenant listing once the write has landed. `expect` is what the test is waiting for:
    * without it a listing read straight after a write can still answer the state from before.
    */
  private def listed(central: CentralApi)(expect: Chunk[Json.Obj] => Boolean): Task[Chunk[Json.Obj]] =
    eventually(find(central))(expect)

  def spec = suite("Central API: tenants")(
    test("a created tenant is listed with the description it was given") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        created <- central.post(path, Fixtures.tenant(id, description = "billing department"))
        listed <- listed(central)(_.exists(t => t.str("id").contains(id) && t.str("description").contains("billing department")))
        _ <- central.delete(path, "tenantId" -> id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(listed.exists(t => t.str("id").contains(id) && t.str("description").contains("billing department")))
          .label("the new tenant must appear in the listing with its description")
    },
    test("creation answers 201 and no body") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        created <- central.post(path, Fixtures.tenant(id))
        _ <- central.delete(path, "tenantId" -> id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(created.body.isEmpty).label("201 must not carry a representation")
    },
    test("the seeded default tenant is present") {
      for
        central <- api
        listed <- find(central)
      yield assertTrue(listed.exists(_.str("id").contains(Fixtures.defaultTenant)))
        .label("every deployment is bootstrapped with a 'default' tenant")
    },
    test("an update replaces the description") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        _ <- central.post(path, Fixtures.tenant(id, description = "before"))
        updated <- central.put(path, Fixtures.tenant(id, description = "after"))
        listed <- listed(central)(_.exists(t => t.str("id").contains(id) && t.str("description").contains("after")))
        _ <- central.delete(path, "tenantId" -> id)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(listed.exists(t => t.str("id").contains(id) && t.str("description").contains("after")))
          .label("the listing must show the new description, not the old one")
    },
    test("an update binds the tenant to an edge") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        edgeId <- CentralApi.id("e2e-edge")
        _ <- central.post("/configuration/edges", Json.Obj("id" -> Json.Str(edgeId)))
        _ <- central.post(path, Fixtures.tenant(tenantId))
        updated <- central.put(path, Fixtures.tenant(tenantId, edgeId = Some(edgeId)))
        listed <- listed(central)(_.exists(t => t.str("id").contains(tenantId) && t.str("edgeId").contains(edgeId)))
        _ <- central.delete(path, "tenantId" -> tenantId)
        _ <- central.delete("/configuration/edges", "edgeId" -> edgeId)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(listed.exists(t => t.str("id").contains(tenantId) && t.str("edgeId").contains(edgeId)))
          .label("sync is edge-scoped, so the binding must be readable back")
    },
    test("an unbound tenant reports a null edge") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        _ <- central.post(path, Fixtures.tenant(id))
        listed <- listed(central)(_.exists(_.str("id").contains(id)))
        _ <- central.delete(path, "tenantId" -> id)
      yield assertTrue(listed.find(_.str("id").contains(id)).exists(t => t.str("edgeId").isEmpty))
        .label("a tenant no edge serves must not name one")
    },
    test("a deleted tenant stops being listed") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        _ <- central.post(path, Fixtures.tenant(id))
        deleted <- central.delete(path, "tenantId" -> id)
        listed <- listed(central)(!_.exists(_.str("id").contains(id)))
      yield assertTrue(deleted.status == Status.NoContent) &&
        assertTrue(!listed.exists(_.str("id").contains(id)))
          .label("the deleted tenant must be gone from the listing")
    },
    test("deleting an unknown tenant is not an error") {
      for
        central <- api
        id <- CentralApi.id("e2e-missing")
        deleted <- central.delete(path, "tenantId" -> id)
      yield assertTrue(deleted.status == Status.NoContent)
        .label("delete is idempotent: the caller's goal is already met")
    },
    test("creating a tenant that already exists does not silently fork it") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        first <- central.post(path, Fixtures.tenant(id, description = "first"))
        second <- central.post(path, Fixtures.tenant(id, description = "second"))
        listed <- listed(central)(_.exists(_.str("id").contains(id)))
        _ <- central.delete(path, "tenantId" -> id)
      yield assertTrue(first.status == Status.Created) &&
        assertTrue(second.status != Status.Created || listed.count(_.str("id").contains(id)) == 1)
          .label("either the duplicate is rejected, or it upserts — never two rows under one id")
    },
    test("an id that breaks the documented pattern is rejected") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.tenant("Not A Tenant"))
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("TenantId is documented as ^[a-z][a-z0-9-]*$")
    },
    test("an id starting with a digit is rejected") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.tenant("1tenant"))
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("TenantId must start with a letter")
    },
    test("a body missing the required description is rejected") {
      for
        central <- api
        id <- CentralApi.id("e2e-tenant")
        rejected <- central.post(path, Json.Obj("id" -> Json.Str(id)))
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("description is a required member of CreateTenantRequest")
    },
    test("a body that is not JSON at all is rejected") {
      for
        central <- api
        rejected <- central.raw(zio.http.Method.POST, path, "not json")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("deleting without the tenantId parameter is rejected") {
      for
        central <- api
        rejected <- central.delete(path)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("tenantId is a required query parameter")
    },
    test("an anonymous caller is refused") {
      for
        central <- api
        listed <- central.anonymous.get(path)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
    test("a caller presenting the wrong secret is refused") {
      for
        central <- api
        listed <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM").get(path)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
