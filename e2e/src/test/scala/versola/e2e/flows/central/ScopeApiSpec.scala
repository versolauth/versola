package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** OAuth scopes and the claims they release, on central's admin API.
  *
  * A scope is the unit a user consents to, and the claims attached to it are what a token
  * carrying that scope is allowed to reveal. Both the scope and its claims are localized,
  * because the consent screen renders them — so these check that the localization patches
  * behave as documented and that a claim cannot quietly widen what a scope releases.
  */
object ScopeApiSpec extends CentralApiSpec:

  private val path = "/configuration/scopes"

  private def find(central: CentralApi, scopeId: String, tenantId: String): Task[Option[Json.Obj]] =
    central.get(path, "tenantId" -> tenantId)
      .flatMap(_.items("scopes"))
      .map(_.find(_.str("scope").contains(scopeId)))

  /** The scope as central reports it once the write has landed. `expect` is what the test is
    * waiting for: without it a read taken straight after an update can still answer the
    * version from before.
    */
  private def read(
      central: CentralApi,
      scopeId: String,
      tenantId: String = Fixtures.defaultTenant,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, scopeId, tenantId))(_.exists(expect))

  /** Waits for the scope to be gone, for the tests that assert an absence. */
  private def gone(central: CentralApi, scopeId: String, tenantId: String = Fixtures.defaultTenant): Task[Option[Json.Obj]] =
    eventually(find(central, scopeId, tenantId))(_.isEmpty)

  private def claimsOf(record: Option[Json.Obj]): Chunk[Json.Obj] =
    record.map(_.objs("claims")).getOrElse(Chunk.empty)

  private def claimNames(record: Option[Json.Obj]): Set[String] =
    claimsOf(record).flatMap(_.str("claim")).toSet

  private def cleanup(central: CentralApi, scopeId: String, tenantId: String = Fixtures.defaultTenant): UIO[Unit] =
    central.delete(path, "tenantId" -> tenantId, "scopeId" -> scopeId).ignore.unit

  def spec = suite("Central API: scopes")(
    test("a created scope is listed with its description") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        created <- central.post(path, Fixtures.scope(id, description = "Read your profile"))
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(record.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Read your profile"))
    },
    test("a scope can be created with no claims") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        created <- central.post(path, Fixtures.scope(id))
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(created.status == Status.Created) && assertTrue(claimsOf(record).isEmpty)
    },
    test("the claims a scope releases are stored with it") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        first <- CentralApi.token("probe_claim")
        second <- CentralApi.token("probe_claim")
        _ <- central.post(
          path,
          Fixtures.scope(id, claims = List(Fixtures.claim(first, "Given name"), Fixtures.claim(second, "Family name"))),
        )
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(claimNames(record) == Set(first, second)) &&
        assertTrue(
          claimsOf(record).find(_.str("claim").contains(first))
            .flatMap(_.obj("description")).flatMap(_.str("en")).contains("Given name"),
        ).label("the consent screen renders a claim's description, so it travels with the claim")
    },
    test("a scope description keeps every language it was given") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(
          path,
          Json.Obj(
            "tenantId" -> Json.Str(Fixtures.defaultTenant),
            "id" -> Json.Str(id),
            "description" -> Json.Obj("en" -> Json.Str("Profile"), "ru" -> Json.Str("Профиль")),
            "claims" -> Json.Arr(Chunk.empty),
          ),
        )
        record <- read(central, id)
        _ <- cleanup(central, id)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).contains("Profile")) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Профиль"))
    },
    test("a scope id outside the documented alphabet is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.scope("Bad Scope"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Scope ID"))
          .label("the scope token travels in a space-delimited `scope` parameter, so it cannot contain a space")
    },
    test("a claim id outside the documented alphabet is refused") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        rejected <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim("Bad Claim"))))
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("a claim id becomes a JWT member name; the alphabet is fixed for the same reason")
    },
    test("a scope without a description is refused") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        rejected <- central.post(
          path,
          Json.Obj("tenantId" -> Json.Str(Fixtures.defaultTenant), "id" -> Json.Str(id)),
        )
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("an undescribed scope cannot be rendered on a consent screen")
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "openid")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update adds a claim to a scope") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        existing <- CentralApi.token("probe_claim")
        added <- CentralApi.token("probe_claim")
        _ <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim(existing))))
        updated <- central.put(path, Fixtures.scopeUpdate(id, add = List(Fixtures.claim(added, "Added"))))
        record <- read(central, id, expect = _.objs("claims").flatMap(_.str("claim")).contains(added))
        _ <- cleanup(central, id)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(claimNames(record) == Set(existing, added))
    },
    test("an update deletes a claim from a scope") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        kept <- CentralApi.token("probe_claim")
        removed <- CentralApi.token("probe_claim")
        _ <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim(kept), Fixtures.claim(removed))))
        _ <- central.put(path, Fixtures.scopeUpdate(id, delete = Set(removed)))
        record <- read(central, id, expect = !_.objs("claims").flatMap(_.str("claim")).contains(removed))
        _ <- cleanup(central, id)
      yield assertTrue(claimNames(record) == Set(kept))
        .label("narrowing what a scope releases has to take effect, or consent understates the grant")
    },
    test("an update rewrites a claim's description") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        claimId <- CentralApi.token("probe_claim")
        _ <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim(claimId, "Before"))))
        _ <- central.put(
          path,
          Fixtures.scopeUpdate(id, update = List(Fixtures.claimPatch(claimId, add = Map("en" -> "After")))),
        )
        record <- read(
          central,
          id,
          expect = _.objs("claims").exists(_.obj("description").exists(_.str("en").contains("After"))),
        )
        _ <- cleanup(central, id)
        description = claimsOf(record).find(_.str("claim").contains(claimId)).flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).contains("After"))
    },
    test("a claim description patch adds a translation without dropping the others") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        claimId <- CentralApi.token("probe_claim")
        _ <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim(claimId, "Given name"))))
        _ <- central.put(
          path,
          Fixtures.scopeUpdate(id, update = List(Fixtures.claimPatch(claimId, add = Map("ru" -> "Имя")))),
        )
        record <- read(central, id, expect = _.objs("claims").exists(_.obj("description").exists(_.has("ru"))))
        _ <- cleanup(central, id)
        description = claimsOf(record).find(_.str("claim").contains(claimId)).flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).contains("Given name")) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Имя"))
          .label("adding a locale is the common edit; it must not wipe the ones already translated")
    },
    test("a claim description patch removes one translation") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        claimId <- CentralApi.token("probe_claim")
        _ <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim(claimId, "Given name"))))
        _ <- central.put(
          path,
          Fixtures.scopeUpdate(
            id,
            update = List(Fixtures.claimPatch(claimId, add = Map("ru" -> "Имя"), delete = Set("en"))),
          ),
        )
        record <- read(central, id, expect = _.objs("claims").exists(_.obj("description").exists(!_.has("en"))))
        _ <- cleanup(central, id)
        description = claimsOf(record).find(_.str("claim").contains(claimId)).flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).isEmpty) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Имя"))
    },
    test("an update patches the scope's own description") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id, description = "Profile"))
        _ <- central.put(path, Fixtures.scopeUpdate(id, description = Fixtures.patchText(add = Map("ru" -> "Профиль"))))
        record <- read(central, id, expect = _.obj("description").exists(_.has("ru")))
        _ <- cleanup(central, id)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).contains("Profile")) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Профиль"))
    },
    test("an update deletes a translation of the scope's description") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id, description = "Profile"))
        _ <- central.put(
          path,
          Fixtures.scopeUpdate(
            id,
            description = Fixtures.patchText(add = Map("ru" -> "Профиль"), delete = Set("en")),
          ),
        )
        record <- read(central, id, expect = _.obj("description").exists(!_.has("en")))
        _ <- cleanup(central, id)
        description = record.flatMap(_.obj("description"))
      yield assertTrue(description.flatMap(_.str("en")).isEmpty) &&
        assertTrue(description.flatMap(_.str("ru")).contains("Профиль"))
    },
    test("one update can add, rewrite and delete claims at once") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        kept <- CentralApi.token("probe_claim")
        removed <- CentralApi.token("probe_claim")
        added <- CentralApi.token("probe_claim")
        _ <- central.post(
          path,
          Fixtures.scope(id, claims = List(Fixtures.claim(kept, "Before"), Fixtures.claim(removed))),
        )
        _ <- central.put(
          path,
          Fixtures.scopeUpdate(
            id,
            add = List(Fixtures.claim(added, "New")),
            update = List(Fixtures.claimPatch(kept, add = Map("en" -> "After"))),
            delete = Set(removed),
          ),
        )
        record <- read(central, id, expect = _.objs("claims").flatMap(_.str("claim")).toSet == Set(kept, added))
        _ <- cleanup(central, id)
        keptDescription = claimsOf(record).find(_.str("claim").contains(kept)).flatMap(_.obj("description"))
      yield assertTrue(claimNames(record) == Set(kept, added)) &&
        assertTrue(keptDescription.flatMap(_.str("en")).contains("After"))
          .label("the console saves a whole edited scope in one request, so all three parts have to apply together")
    },
    test("deleting a claim the scope never had is not an error") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id))
        updated <- central.put(path, Fixtures.scopeUpdate(id, delete = Set("probe_never_existed")))
        _ <- cleanup(central, id)
      yield assertTrue(updated.status == Status.NoContent)
    },
    test("rewriting a claim the scope never had is not an error") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id))
        updated <- central.put(
          path,
          Fixtures.scopeUpdate(id, update = List(Fixtures.claimPatch("probe_never_existed", Map("en" -> "x")))),
        )
        record <- read(central, id, expect = _.objs("claims").isEmpty)
        _ <- cleanup(central, id)
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(claimsOf(record).isEmpty)
          .label("a no-op update must not conjure the claim it was asked to edit")
    },
    test("an update without the required patch member is refused") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        rejected <- central.put(
          path,
          Json.Obj("tenantId" -> Json.Str(Fixtures.defaultTenant), "id" -> Json.Str(id)),
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
    test("a scope is not listed under a tenant it does not belong to") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        id <- CentralApi.token("probe_scope")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.scope(id, tenantId = tenantId))
        own <- read(central, id, tenantId)
        other <- gone(central, id)
        _ <- cleanup(central, id, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(own.nonEmpty) &&
        assertTrue(other.isEmpty)
          .label("scope names are tenant-local: two tenants may define the same token differently")
    },
    test("the same scope token can exist in two tenants at once") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        id <- CentralApi.token("probe_scope")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        inDefault <- central.post(path, Fixtures.scope(id, description = "Default's meaning"))
        inOther <- central.post(path, Fixtures.scope(id, tenantId = tenantId, description = "Other's meaning"))
        defaultRecord <- read(central, id, expect = _.obj("description").exists(_.str("en").contains("Default's meaning")))
        otherRecord <- read(central, id, tenantId, expect = _.obj("description").exists(_.str("en").contains("Other's meaning")))
        _ <- cleanup(central, id)
        _ <- cleanup(central, id, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(inDefault.status == Status.Created && inOther.status == Status.Created) &&
        assertTrue(defaultRecord.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Default's meaning")) &&
        assertTrue(otherRecord.flatMap(_.obj("description")).flatMap(_.str("en")).contains("Other's meaning"))
    },
    test("limit caps the number of scopes returned") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id))
        listed <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "limit" -> "1").flatMap(_.items("scopes"))
        _ <- cleanup(central, id)
      yield assertTrue(listed.size == 1)
    },
    test("a deleted scope stops being listed") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id))
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "scopeId" -> id)
        record <- gone(central, id)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting a scope takes its claims with it") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        claimId <- CentralApi.token("probe_claim")
        _ <- central.post(path, Fixtures.scope(id, claims = List(Fixtures.claim(claimId))))
        _ <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "scopeId" -> id)
        again <- central.post(path, Fixtures.scope(id))
        record <- read(central, id, expect = _.objs("claims").isEmpty)
        _ <- cleanup(central, id)
      yield assertTrue(again.status == Status.Created) &&
        assertTrue(claimsOf(record).isEmpty)
          .label("an orphaned claim would reappear under a scope recreated with the same token")
    },
    test("deleting an unknown scope is not an error") {
      for
        central <- api
        id <- CentralApi.token("probe_absent")
        deleted <- central.delete(path, "tenantId" -> Fixtures.defaultTenant, "scopeId" -> id)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("deleting without a scopeId is refused") {
      for
        central <- api
        rejected <- central.delete(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("scopeId"))
    },
    test("an anonymous caller cannot list scopes") {
      for
        central <- api
        listed <- central.anonymous.get(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
    test("an anonymous caller cannot create a scope") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        rejected <- central.anonymous.post(path, Fixtures.scope(id))
        record <- gone(central, id)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.isEmpty)
    },
    test("a caller presenting the wrong secret cannot delete a scope") {
      for
        central <- api
        id <- CentralApi.token("probe_scope")
        _ <- central.post(path, Fixtures.scope(id))
        rejected <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
          .delete(path, "tenantId" -> Fixtures.defaultTenant, "scopeId" -> id)
        record <- read(central, id)
        _ <- cleanup(central, id)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.nonEmpty)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
