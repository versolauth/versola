package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** RFC 9396 authorization detail types on central's admin API.
  *
  * Each type carries a JSON Schema that auth validates a requested `authorization_details`
  * entry against. A schema that is not itself a valid schema would fail open or closed at
  * request time with no way to tell which, so central compiles it on write — that is what
  * most of these check.
  */
object AuthorizationDetailTypeApiSpec extends CentralApiSpec:

  private val path = "/configuration/authorization-detail-types"

  private def find(central: CentralApi, name: String, tenantId: String): Task[Option[Json.Obj]] =
    central.get(path, "tenantId" -> tenantId)
      .flatMap(_.items("types"))
      .map(_.find(_.str("type").contains(name)))

  /** The type as central reports it once the write has landed. `expect` is what the test is
    * waiting for: without it a read taken straight after an update can still answer the
    * version from before.
    */
  private def read(
      central: CentralApi,
      name: String,
      tenantId: String = Fixtures.defaultTenant,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, name, tenantId))(_.exists(expect))

  /** Waits for the type to be gone, for the tests that assert an absence. */
  private def gone(central: CentralApi, name: String, tenantId: String = Fixtures.defaultTenant): Task[Option[Json.Obj]] =
    eventually(find(central, name, tenantId))(_.isEmpty)

  private def cleanup(central: CentralApi, name: String, tenantId: String = Fixtures.defaultTenant): UIO[Unit] =
    central.delete(path, "tenantId" -> tenantId, "type" -> name).ignore.unit

  private val ibanSchema: Json.Obj =
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj("iban" -> Json.Obj("type" -> Json.Str("string"))),
      "required" -> Json.Arr(Chunk(Json.Str("iban"))),
    )

  def spec = suite("Central API: authorization detail types")(
    test("a created type is listed with its description") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        created <- central.post(path, Fixtures.detailType(name, description = "Payment initiation"))
        record <- read(central, name)
        _ <- cleanup(central, name)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(record.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Payment initiation"))
    },
    test("the schema survives the round trip intact") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name))
        record <- read(central, name)
        _ <- cleanup(central, name)
        schema = record.flatMap(_.obj("schema"))
      yield assertTrue(schema.flatMap(_.str("type")).contains("object")) &&
        assertTrue(schema.map(_.strings("required")).contains(Set("amount"))) &&
        assertTrue(schema.flatMap(_.obj("properties")).flatMap(_.obj("amount")).flatMap(_.str("type")).contains("number"))
          .label("auth validates a request against this document verbatim; a lossy round trip changes the contract")
    },
    test("a schema that is not a valid JSON Schema is refused") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        rejected <- central.post(
          path,
          Fixtures.detailType(name, schema = Json.Obj("type" -> Json.Str("not-a-type"))),
        )
        record <- gone(central, name)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("InvalidSchema")) &&
        assertTrue(record.isEmpty)
    },
    test("the rejection lists what is wrong with the schema") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        rejected <- central.post(path, Fixtures.detailType(name, schema = Json.Obj("type" -> Json.Num(42))))
      yield assertTrue(rejected.body.contains("enumeration"))
        .label("an operator pasting a schema into a form needs the validator's findings, not a bare 400")
    },
    test("a schema with no constraints at all is accepted") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        created <- central.post(path, Fixtures.detailType(name, schema = Json.Obj()))
        _ <- cleanup(central, name)
      yield assertTrue(created.status == Status.Created)
        .label("an empty schema accepts anything, which is a legitimate starting point for a new type")
    },
    test("a type name outside the documented alphabet is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.detailType("Bad Type"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Authorization detail type"))
    },
    test("a type without a schema is refused") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        rejected <- central.post(
          path,
          Json.Obj(
            "tenantId" -> Json.Str(Fixtures.defaultTenant),
            "type" -> Json.Str(name),
            "description" -> Fixtures.text("no schema"),
          ),
        )
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("a type with no schema would accept any detail claiming that type")
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "payment_initiation")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update replaces the schema") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name))
        updated <- central.put(path, Fixtures.detailType(name, schema = ibanSchema))
        record <- read(central, name, expect = _.obj("schema").exists(_.strings("required") == Set("iban")))
        _ <- cleanup(central, name)
        schema = record.flatMap(_.obj("schema"))
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(schema.map(_.strings("required")).contains(Set("iban")))
    },
    test("an update replaces the description outright") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name, description = "Before"))
        _ <- central.put(path, Fixtures.detailType(name, description = "After"))
        record <- read(central, name, expect = _.obj("description").exists(_.str("en").contains("After")))
        _ <- cleanup(central, name)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).contains("After")) &&
        assertTrue(description.map(_.fields.size).contains(1))
          .label("this member is a replacement, not a patch: a stale translation must not survive")
    },
    test("an update refuses a schema it would not have accepted at creation") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name))
        rejected <- central.put(path, Fixtures.detailType(name, schema = Json.Obj("type" -> Json.Num(42))))
        record <- read(central, name)
        _ <- cleanup(central, name)
        schema = record.flatMap(_.obj("schema"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(schema.map(_.strings("required")).contains(Set("amount")))
          .label("validation that only runs on create is validation an update can bypass")
    },
    test("an update of an unknown type does not create one") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        updated <- central.put(path, Fixtures.detailType(name))
        record <- gone(central, name)
      yield assertTrue(updated.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("the listing is scoped to one tenant and demands to be told which") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
    },
    test("a type is not listed under a tenant it does not belong to") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        name <- CentralApi.token("probe_detail")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.detailType(name, tenantId = tenantId))
        own <- read(central, name, tenantId)
        other <- gone(central, name)
        _ <- cleanup(central, name, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(own.nonEmpty) && assertTrue(other.isEmpty)
    },
    test("two tenants can define the same type name with different schemas") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        name <- CentralApi.token("probe_detail")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.detailType(name))
        _ <- central.post(path, Fixtures.detailType(name, tenantId = tenantId, schema = ibanSchema))
        inDefault <- read(central, name, expect = _.obj("schema").exists(_.strings("required") == Set("amount")))
        inOther <- read(central, name, tenantId, expect = _.obj("schema").exists(_.strings("required") == Set("iban")))
        _ <- cleanup(central, name)
        _ <- cleanup(central, name, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(inDefault.flatMap(_.obj("schema")).map(_.strings("required")).contains(Set("amount"))) &&
        assertTrue(inOther.flatMap(_.obj("schema")).map(_.strings("required")).contains(Set("iban")))
          .label("`payment_initiation` means whatever the tenant says it means, so the schemas must not collide")
    },
    test("limit caps the number of types returned") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name))
        listed <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "limit" -> "1").flatMap(_.items("types"))
        _ <- cleanup(central, name)
      yield assertTrue(listed.size == 1)
    },
    test("a deleted type stops being listed") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name))
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "type" -> name)
        record <- gone(central, name)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting an unknown type is not an error") {
      for
        central <- api
        name <- CentralApi.token("probe_absent")
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "type" -> name)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("deleting without naming the type is refused") {
      for
        central <- api
        rejected <- central.delete(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("type"))
    },
    test("an anonymous caller cannot list types") {
      for
        central <- api
        listed <- central.anonymous.get(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
    test("an anonymous caller cannot create a type") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        rejected <- central.anonymous.post(path, Fixtures.detailType(name))
        record <- gone(central, name)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.isEmpty)
    },
    test("a caller presenting the wrong secret cannot loosen a schema") {
      for
        central <- api
        name <- CentralApi.token("probe_detail")
        _ <- central.post(path, Fixtures.detailType(name))
        rejected <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
          .put(path, Fixtures.detailType(name, schema = Json.Obj()))
        record <- read(central, name)
        _ <- cleanup(central, name)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.flatMap(_.obj("schema")).map(_.strings("required")).contains(Set("amount")))
          .label("an empty schema accepts any detail, so this write is a bypass of the whole type check")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
