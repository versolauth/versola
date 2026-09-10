package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** Edge registration on central's admin API.
  *
  * Registering an edge mints an RSA key pair: central keeps the public half and hands the
  * private half over exactly once. That key is what lets an edge pull its tenants' client and
  * resource secrets, so the registry's job is to keep exactly one active key per edge and to
  * make a rotation visible while the old key is still accepted.
  */
object EdgeRegistryApiSpec extends CentralApiSpec:

  private val path = "/configuration/edges"

  private def register(id: String): Json.Obj =
    Json.Obj("id" -> Json.Str(id))

  private def find(central: CentralApi, edgeId: String): Task[Option[Json.Obj]] =
    central.get(path).flatMap(_.items("edges")).map(_.find(_.str("id").contains(edgeId)))

  /** The edge as central reports it once the write has landed. `expect` is what the test is
    * waiting for: without it a read taken straight after a rotation can still answer the
    * state from before.
    */
  private def read(
      central: CentralApi,
      edgeId: String,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, edgeId))(_.exists(expect))

  /** Waits for the edge to be gone, for the tests that assert an absence. */
  private def gone(central: CentralApi, edgeId: String): Task[Option[Json.Obj]] =
    eventually(find(central, edgeId))(_.isEmpty)

  private def cleanup(central: CentralApi, edgeId: String): UIO[Unit] =
    central.delete(path, "edgeId" -> edgeId).ignore.unit

  def spec = suite("Central API: edge registry")(
    test("registration answers 201 with a key the edge can authenticate with") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        created <- central.post(path, register(id))
        body <- created.obj
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(body.str("keyId").exists(_.nonEmpty)) &&
        assertTrue(body.str("privateKey").exists(_.nonEmpty))
          .label("central keeps only the public half, so this response is the only chance to get the private one")
    },
    test("a registered edge is listed with no old key outstanding") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(record.nonEmpty) &&
        assertTrue(record.flatMap(_.bool("hasOldKey")).contains(false))
    },
    test("the listing does not carry any private key material") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        listed <- central.get(path)
        _ <- cleanup(central, id)
      yield assertTrue(!listed.body.contains("privateKey"))
        .label("a private key readable from an admin listing would defeat handing it out once")
    },
    test("two edges registered back to back get different keys") {
      for
        central <- api
        first <- CentralApi.id("e2e-edge")
        second <- CentralApi.id("e2e-edge")
        one <- central.post(path, register(first)).flatMap(_.stringAt("privateKey"))
        two <- central.post(path, register(second)).flatMap(_.stringAt("privateKey"))
        _ <- cleanup(central, first)
        _ <- cleanup(central, second)
      yield assertTrue(one != two)
        .label("a shared key would let one edge decrypt another's tenants' secrets")
    },
    test("rotating a key answers a different private key") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        original <- central.post(path, register(id)).flatMap(_.stringAt("privateKey"))
        rotated <- central.postEmpty(s"$path/rotate-key", "edgeId" -> id)
        replacement <- rotated.stringAt("privateKey")
        _ <- cleanup(central, id)
      yield assertTrue(rotated.status == Status.Ok) && assertTrue(replacement != original)
    },
    test("a rotated edge reports an old key still outstanding") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        _ <- central.postEmpty(s"$path/rotate-key", "edgeId" -> id)
        record <- read(central, id, expect = _.bool("hasOldKey").contains(true))
        _ <- cleanup(central, id)
      yield assertTrue(record.flatMap(_.bool("hasOldKey")).contains(true))
        .label("the old key keeps working until it is dropped, so an operator has to see that it is still live")
    },
    test("dropping the old key clears the rotation") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        _ <- central.postEmpty(s"$path/rotate-key", "edgeId" -> id)
        dropped <- central.delete(s"$path/old-key", "edgeId" -> id)
        record <- read(central, id, expect = _.bool("hasOldKey").contains(false))
        _ <- cleanup(central, id)
      yield assertTrue(dropped.status == Status.NoContent) &&
        assertTrue(record.flatMap(_.bool("hasOldKey")).contains(false))
    },
    test("dropping an old key that was never minted is not an error") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        dropped <- central.delete(s"$path/old-key", "edgeId" -> id)
        _ <- cleanup(central, id)
      yield assertTrue(dropped.status == Status.NoContent)
    },
    test("rotating twice in a row leaves only one old key") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        first <- central.postEmpty(s"$path/rotate-key", "edgeId" -> id).flatMap(_.stringAt("privateKey"))
        second <- central.postEmpty(s"$path/rotate-key", "edgeId" -> id).flatMap(_.stringAt("privateKey"))
        _ <- central.delete(s"$path/old-key", "edgeId" -> id)
        record <- read(central, id, expect = _.bool("hasOldKey").contains(false))
        _ <- cleanup(central, id)
      yield assertTrue(first != second) &&
        assertTrue(record.flatMap(_.bool("hasOldKey")).contains(false))
          .label("only the immediately previous key is retained; one drop has to be enough to close a rotation")
    },
    test("rotating the key of an unknown edge does not register one") {
      for
        central <- api
        id <- CentralApi.id("e2e-absent")
        _ <- central.postEmpty(s"$path/rotate-key", "edgeId" -> id)
        record <- gone(central, id)
      yield assertTrue(record.isEmpty)
        .label("an edge that appeared without being registered would be an unaudited secret consumer")
    },
    test("an edge id outside the documented alphabet is refused") {
      for
        central <- api
        rejected <- central.post(path, register("Bad Edge"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Edge ID"))
    },
    test("a body without an id is refused") {
      for
        central <- api
        rejected <- central.post(path, Json.Obj())
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "probe-edge")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a deleted edge stops being listed") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        deleted <- central.delete(path, "edgeId" -> id)
        record <- gone(central, id)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting an unknown edge is not an error") {
      for
        central <- api
        id <- CentralApi.id("e2e-absent")
        deleted <- central.delete(path, "edgeId" -> id)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("rotating without an edgeId is refused") {
      for
        central <- api
        rejected <- central.postEmpty(s"$path/rotate-key")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("deleting a bound edge leaves the tenant behind without one") {
      for
        central <- api
        edgeId <- CentralApi.id("e2e-edge")
        tenantId <- CentralApi.id("e2e-tenant")
        _ <- central.post(path, register(edgeId))
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.put("/configuration/tenants", Fixtures.tenant(tenantId, edgeId = Some(edgeId)))
        _ <- central.delete(path, "edgeId" -> edgeId)
        tenants <- eventually(central.get("/configuration/tenants").flatMap(_.items("tenants")))(
          _.find(_.str("id").contains(tenantId)).exists(_.str("edgeId").isEmpty),
        )
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
        tenant = tenants.find(_.str("id").contains(tenantId))
      yield assertTrue(tenant.nonEmpty) &&
        assertTrue(tenant.flatMap(_.str("edgeId")).isEmpty)
          .label("decommissioning an edge must not take its tenants' configuration down with it")
    },
    test("an anonymous caller cannot list edges") {
      for
        central <- api
        listed <- central.anonymous.get(path)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
    test("an anonymous caller cannot register an edge") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        rejected <- central.anonymous.post(path, register(id))
        record <- gone(central, id)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.isEmpty).label("an edge anyone can register is an edge anyone can read secrets through")
    },
    test("a caller presenting the wrong secret cannot rotate an edge key") {
      for
        central <- api
        id <- CentralApi.id("e2e-edge")
        _ <- central.post(path, register(id))
        rejected <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
          .postEmpty(s"$path/rotate-key", "edgeId" -> id)
        record <- read(central, id, expect = _.bool("hasOldKey").contains(false))
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.flatMap(_.bool("hasOldKey")).contains(false))
          .label("an unauthenticated rotation would cut a running edge off from its secrets")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
