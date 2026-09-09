package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** Protected resources and their endpoints on central's admin API.
  *
  * A resource record is the edge's routing and authorization table: which upstream a request
  * goes to, which endpoint pattern it matches, and which CEL rules gate it. Central validates
  * that table on write — an endpoint whose CEL expression does not compile, or whose path is
  * indistinguishable from another's, would only fail once a live request hit it.
  */
object ResourceApiSpec extends CentralApiSpec:

  private val path = "/configuration/resources"

  private def find(central: CentralApi, resourceId: String): Task[Option[Json.Obj]] =
    central.get(path, "tenantId" -> Fixtures.defaultTenant)
      .flatMap(_.items("resources"))
      .map(_.find(_.str("resourceId").contains(resourceId)))

  /** The resource as central reports it once the write has landed. `expect` is what the test is
    * waiting for: without it a read taken straight after an update can still answer the
    * version from before.
    */
  private def read(
      central: CentralApi,
      resourceId: String,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, resourceId))(_.exists(expect))

  /** Waits for the resource to be gone, for the tests that assert an absence. */
  private def gone(central: CentralApi, resourceId: String): Task[Option[Json.Obj]] =
    eventually(find(central, resourceId))(_.isEmpty)

  private def endpointsOf(record: Option[Json.Obj]): Chunk[Json.Obj] =
    record.map(_.objs("endpoints")).getOrElse(Chunk.empty)

  /** A resource id together with the upstream origin derived from it, so no two fixtures
    * claim the same URI — auth resolves a requested audience by URI, and a duplicate would
    * make that resolution ambiguous.
    */
  private def resourceId: UIO[(String, String)] =
    CentralApi.id("e2e-resource").map(id => id -> s"https://$id.test")

  private def cleanup(central: CentralApi, resourceId: String): UIO[Unit] =
    central.delete(path, "resourceId" -> resourceId).ignore.unit

  def spec = suite("Central API: resources")(
    test("a public resource is created without a secret") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        created <- central.post(path, Fixtures.resource(id, uri))
        body <- created.obj
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(body.str("resourceId").contains(id)) &&
        assertTrue(!body.has("secret"))
          .label("a resource behind the edge authenticates its callers by token, not by a shared secret")
    },
    test("an internal resource is created with a secret to authenticate the edge") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        created <- central.post(path, Fixtures.resource(id, uri, internal = true))
        secret <- created.stringAt("secret")
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(secret.nonEmpty)
          .label("an internal resource trusts the edge itself, so the edge needs a credential for it")
    },
    test("a created resource reads back with its upstream origin and audience") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        clientId <- CentralApi.id("e2e-client")
        _ <- central.post("/configuration/clients", Fixtures.client(clientId))
        _ <- central.post(path, Fixtures.resource(id, uri, audience = Set(clientId)))
        record <- read(central, id)
        _ <- cleanup(central, id)
        _ <- central.delete("/configuration/clients", "clientId" -> clientId)
      yield assertTrue(record.flatMap(_.str("resource")).contains(uri)) &&
        assertTrue(record.map(_.strings("audience")).contains(Set(clientId)))
          .label("the audience decides which clients may ask for a token for this resource")
    },
    test("an endpoint reads back with every rule it was configured with") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(
                endpointId,
                method = "POST",
                path = "/orders",
                fetchUserInfo = true,
                allow = Some("token.sub != ''"),
                inject = List(Fixtures.inject("header", "X-Subject", "token.sub")),
                stepUpCondition = Some("true"),
                stepUpAcr = Some(Acr.PasskeyLevel),
                maxAge = Some(300),
              ),
            ),
          ),
        )
        record <- read(central, id)
        endpoint = endpointsOf(record).headOption
        _ <- cleanup(central, id)
      yield assertTrue(endpoint.flatMap(_.str("method")).contains("POST")) &&
        assertTrue(endpoint.flatMap(_.str("path")).contains("/orders")) &&
        assertTrue(endpoint.flatMap(_.bool("fetchUserInfo")).contains(true)) &&
        assertTrue(endpoint.flatMap(_.str("allow")).contains("token.sub != ''")) &&
        assertTrue(endpoint.flatMap(_.str("stepUpCondition")).contains("true")) &&
        assertTrue(endpoint.flatMap(_.str("stepUpAcr")).contains(Acr.PasskeyLevel)) &&
        assertTrue(endpoint.flatMap(_.int("maxAge")).contains(300)) &&
        assertTrue(endpoint.map(_.objs("inject")).exists(_.exists(_.str("name").contains("X-Subject"))))
          .label("every one of these changes what the edge lets through, so none may be dropped")
    },
    test("a resource can be created with no endpoints at all") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        created <- central.post(path, Fixtures.resource(id, uri))
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(endpointsOf(record).isEmpty)
          .label("endpoints are added later; refusing an empty resource would force a chicken-and-egg registration")
    },
    test("several endpoints are stored for one resource") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        first <- CentralApi.uuid.map(_.toString)
        second <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(first, path = "/items"),
              Fixtures.endpoint(second, method = "POST", path = "/items"),
            ),
          ),
        )
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(endpointsOf(record).size == 2) &&
        assertTrue(endpointsOf(record).flatMap(_.str("method")).toSet == Set("GET", "POST"))
          .label("the same path under two methods is two distinct endpoints, each with its own rules")
    },
    test("the reserved edge resource id is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.resource("edge", "https://edge-impostor.test"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("ReservedResourceId"))
          .label("`resource://edge` is the edge's own audience; a resource claiming it could borrow edge tokens")
    },
    test("a resource id outside the documented alphabet is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.resource("Bad Resource", "https://bad.test"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Resource ID"))
    },
    test("an upstream origin that is not a URI is refused") {
      for
        central <- api
        ids <- resourceId
        (id, _) = ids
        rejected <- central.post(path, Fixtures.resource(id, "not a uri"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("resource"))
    },
    test("an endpoint path that is not rooted is refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        rejected <- central.post(
          path,
          Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(endpointId, path = "items"))),
        )
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("InvalidEndpointPath")) &&
        assertTrue(rejected.body.contains(endpointId))
          .label("the error has to identify which endpoint of the submitted set is at fault")
    },
    test("an allow expression that does not compile is refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        rejected <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(Fixtures.endpoint(endpointId, allow = Some("this is not cel ==="))),
          ),
        )
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("InvalidAllowExpression"))
          .label("an uncompilable rule is evaluated as a denial at runtime, locking the endpoint out silently")
    },
    test("the rejection of an allow expression explains where it broke") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        rejected <- central.post(
          path,
          Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(endpointId, allow = Some("token.sub =")))),
        )
        _ <- cleanup(central, id)
      yield assertTrue(rejected.body.contains("ERROR")) &&
        assertTrue(rejected.body.contains(endpointId))
          .label("an operator writing CEL in a form field needs the compiler's message, not a generic 400")
    },
    test("an inject expression that does not compile is refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        rejected <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(endpointId, inject = List(Fixtures.inject("header", "X-Subject", "@@@"))),
            ),
          ),
        )
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("InvalidInjectExpression")) &&
        assertTrue(rejected.body.contains("X-Subject"))
          .label("the rule name has to be reported: an endpoint may carry several inject rules")
    },
    test("a step-up condition that does not compile is refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        rejected <- central.post(
          path,
          Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(endpointId, stepUpCondition = Some("%%%")))),
        )
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("InvalidStepUpConditionExpression"))
    },
    test("two endpoints that differ only in their path parameter names are refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        first <- CentralApi.uuid.map(_.toString)
        second <- CentralApi.uuid.map(_.toString)
        rejected <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(first, path = "/items/{id}"),
              Fixtures.endpoint(second, path = "/items/{key}"),
            ),
          ),
        )
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("AmbiguousEndpointPath"))
          .label("both match the same requests, so which rules apply would come down to storage order")
    },
    test("the same path under different methods is not ambiguous") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        first <- CentralApi.uuid.map(_.toString)
        second <- CentralApi.uuid.map(_.toString)
        created <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(first, method = "GET", path = "/items/{id}"),
              Fixtures.endpoint(second, method = "DELETE", path = "/items/{id}"),
            ),
          ),
        )
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created)
        .label("read and delete of one path routinely need different permissions, so both must be registrable")
    },
    test("a literal segment and a parameter segment coexist at the same position") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        first <- CentralApi.uuid.map(_.toString)
        second <- CentralApi.uuid.map(_.toString)
        created <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(first, path = "/items/me"),
              Fixtures.endpoint(second, path = "/items/{id}"),
            ),
          ),
        )
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created)
        .label("`/items/me` is more specific than `/items/{id}`; the edge resolves that by specificity")
    },
    test("an update replaces the upstream origin") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post(path, Fixtures.resource(id, uri))
        updated <- central.put(path, Fixtures.resourceUpdate(id, resource = Some(s"$uri:8443")))
        record <- read(central, id, expect = _.str("resource").contains(s"$uri:8443"))
        _ <- cleanup(central, id)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.flatMap(_.str("resource")).contains(s"$uri:8443"))
    },
    test("an update replaces the audience outright") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        clientId <- CentralApi.id("e2e-client")
        _ <- central.post("/configuration/clients", Fixtures.client(clientId))
        _ <- central.post(path, Fixtures.resource(id, uri, audience = Set(clientId)))
        _ <- central.put(path, Fixtures.resourceUpdate(id, audience = Some(Set.empty)))
        record <- read(central, id, expect = _.strings("audience").isEmpty)
        _ <- cleanup(central, id)
        _ <- central.delete("/configuration/clients", "clientId" -> clientId)
      yield assertTrue(record.map(_.strings("audience")).contains(Set.empty[String]))
        .label("revoking a client's access to a resource has to be possible in one call")
    },
    test("an update adds an endpoint to an existing resource") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        existing <- CentralApi.uuid.map(_.toString)
        added <- CentralApi.uuid.map(_.toString)
        _ <- central.post(path, Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(existing))))
        _ <- central.put(
          path,
          Fixtures.resourceUpdate(id, createEndpoints = List(Fixtures.endpoint(added, path = "/orders"))),
        )
        record <- read(central, id, expect = _.objs("endpoints").flatMap(_.str("id")).toSet == Set(existing, added))
        _ <- cleanup(central, id)
      yield assertTrue(endpointsOf(record).flatMap(_.str("id")).toSet == Set(existing, added))
    },
    test("an update deletes an endpoint") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        kept <- CentralApi.uuid.map(_.toString)
        removed <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(Fixtures.endpoint(kept, path = "/items"), Fixtures.endpoint(removed, path = "/orders")),
          ),
        )
        _ <- central.put(path, Fixtures.resourceUpdate(id, deleteEndpoints = Set(removed)))
        record <- read(central, id, expect = _.objs("endpoints").flatMap(_.str("id")).toSet == Set(kept))
        _ <- cleanup(central, id)
      yield assertTrue(endpointsOf(record).flatMap(_.str("id")).toSet == Set(kept))
    },
    test("an update swaps one endpoint for another in a single call") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        before <- CentralApi.uuid.map(_.toString)
        after <- CentralApi.uuid.map(_.toString)
        _ <- central.post(path, Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(before, path = "/v1"))))
        updated <- central.put(
          path,
          Fixtures.resourceUpdate(
            id,
            deleteEndpoints = Set(before),
            createEndpoints = List(Fixtures.endpoint(after, path = "/v1")),
          ),
        )
        record <- read(central, id, expect = _.objs("endpoints").flatMap(_.str("id")).toSet == Set(after))
        _ <- cleanup(central, id)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(endpointsOf(record).flatMap(_.str("id")).toSet == Set(after))
          .label("re-pointing a path at new rules must not leave the endpoint unreachable in between")
    },
    test("an update refuses an endpoint whose CEL does not compile") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        endpointId <- CentralApi.uuid.map(_.toString)
        _ <- central.post(path, Fixtures.resource(id, uri))
        rejected <- central.put(
          path,
          Fixtures.resourceUpdate(
            id,
            createEndpoints = List(Fixtures.endpoint(endpointId, allow = Some("not cel ==="))),
          ),
        )
        record <- read(central, id, expect = _.objs("endpoints").isEmpty)
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(endpointsOf(record).isEmpty)
          .label("validation that only runs on create is validation an update can bypass")
    },
    test("an update refuses to introduce an ambiguous endpoint path") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        existing <- CentralApi.uuid.map(_.toString)
        conflicting <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          path,
          Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(existing, path = "/items/{id}"))),
        )
        rejected <- central.put(
          path,
          Fixtures.resourceUpdate(
            id,
            createEndpoints = List(Fixtures.endpoint(conflicting, path = "/items/{key}")),
          ),
        )
        record <- read(central, id, expect = _.objs("endpoints").size == 1)
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(endpointsOf(record).size == 1)
          .label("ambiguity is a property of the whole set, so the check has to include what is already stored")
    },
    test("an endpoint deleted in the same update no longer conflicts with the one replacing it") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        before <- CentralApi.uuid.map(_.toString)
        after <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          path,
          Fixtures.resource(id, uri, endpoints = List(Fixtures.endpoint(before, path = "/items/{id}"))),
        )
        updated <- central.put(
          path,
          Fixtures.resourceUpdate(
            id,
            deleteEndpoints = Set(before),
            createEndpoints = List(Fixtures.endpoint(after, path = "/items/{key}")),
          ),
        )
        _ <- cleanup(central, id)
      yield assertTrue(updated.status == Status.NoContent)
        .label("renaming a path parameter is a legitimate edit; the check must consider the post-update set")
    },
    test("an update of an unknown resource does not create one") {
      for
        central <- api
        ids <- resourceId
        (id, _) = ids
        _ <- central.put(path, Fixtures.resourceUpdate(id, resource = Some("https://conjured.test")))
        record <- gone(central, id)
      yield assertTrue(record.isEmpty)
        .label("an update is not a back door for registering a resource without its validation")
    },
    test("rotating an internal resource's secret answers a new one") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        original <- central.post(path, Fixtures.resource(id, uri, internal = true)).flatMap(_.stringAt("secret"))
        rotated <- central.postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        replacement <- rotated.stringAt("secret")
        record <- read(central, id, expect = _.bool("secretRotation").contains(true))
        _ <- cleanup(central, id)
      yield assertTrue(rotated.status == Status.Ok) &&
        assertTrue(replacement != original) &&
        assertTrue(record.flatMap(_.bool("secretRotation")).contains(true))
    },
    test("a second rotation before the first is retired is refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post(path, Fixtures.resource(id, uri, internal = true))
        _ <- central.postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        second <- central.postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        _ <- cleanup(central, id)
      yield assertTrue(second.status == Status.Conflict)
        .label("only one previous secret is kept; a second rotation would strand callers still on it")
    },
    test("retiring the previous secret makes another rotation possible") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post(path, Fixtures.resource(id, uri, internal = true))
        _ <- central.postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        retired <- central.delete(s"$path/previous-secret", "resourceId" -> id)
        again <- central.postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        _ <- cleanup(central, id)
      yield assertTrue(retired.status == Status.NoContent) &&
        assertTrue(again.status == Status.Ok)
    },
    test("rotating the secret of a resource that has none is refused") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post(path, Fixtures.resource(id, uri))
        rejected <- central.postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.Conflict)
        .label("a public resource has no secret to rotate; answering 200 would imply one exists")
    },
    test("a deleted resource stops being listed") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post(path, Fixtures.resource(id, uri))
        deleted <- central.delete(path, "resourceId" -> id)
        record <- gone(central, id)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting an unknown resource is not an error") {
      for
        central <- api
        ids <- resourceId
        (id, _) = ids
        deleted <- central.delete(path, "resourceId" -> id)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("the listing is scoped to one tenant and demands to be told which") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
    },
    test("a resource is not listed under a tenant it does not belong to") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.resource(id, uri, tenantId = tenantId))
        own <- eventually(central.get(path, "tenantId" -> tenantId).flatMap(_.items("resources")))(
          _.exists(_.str("resourceId").contains(id)),
        )
        other <- gone(central, id)
        _ <- cleanup(central, id)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(own.exists(_.str("resourceId").contains(id))) && assertTrue(other.isEmpty)
    },
    test("the listing orders an endpoint set predictably") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        first <- CentralApi.uuid.map(_.toString)
        second <- CentralApi.uuid.map(_.toString)
        third <- CentralApi.uuid.map(_.toString)
        _ <- central.post(
          path,
          Fixtures.resource(
            id,
            uri,
            endpoints = List(
              Fixtures.endpoint(first, path = "/zebra"),
              Fixtures.endpoint(second, path = "/apple"),
              Fixtures.endpoint(third, method = "POST", path = "/apple"),
            ),
          ),
        )
        record <- read(central, id, expect = _.objs("endpoints").size == 3)
        _ <- cleanup(central, id)
        paths = endpointsOf(record).flatMap(endpoint => endpoint.str("path").zip(endpoint.str("method")))
      yield assertTrue(paths == Chunk("/apple" -> "GET", "/apple" -> "POST", "/zebra" -> "GET"))
        .label("the console renders this list; a stable order keeps rows from jumping between reloads")
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "[]")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an anonymous caller cannot list resources") {
      for
        central <- api
        listed <- central.anonymous.get(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(listed.status == Status.Unauthorized)
        .label("the listing exposes the internal topology of every upstream behind the edge")
    },
    test("an anonymous caller cannot create a resource") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        rejected <- central.anonymous.post(path, Fixtures.resource(id, uri))
        record <- gone(central, id)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.isEmpty)
    },
    test("a caller presenting the wrong secret cannot rotate a resource secret") {
      for
        central <- api
        ids <- resourceId
        (id, uri) = ids
        _ <- central.post(path, Fixtures.resource(id, uri, internal = true))
        rejected <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
          .postEmpty(s"$path/rotate-secret", "resourceId" -> id)
        record <- read(central, id, expect = _.bool("secretRotation").contains(false))
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.flatMap(_.bool("secretRotation")).contains(false))
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
