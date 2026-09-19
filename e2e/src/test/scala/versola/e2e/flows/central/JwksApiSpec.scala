package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** The central-owned JWKS: generating a keypair, reading back which keys can sign, choosing a
  * tenant's signing key, and refusing to delete a key still in use.
  *
  * The unit tests prove these rules against stubs. What only a live central can show is that
  * a generated key survives the Postgres round-trip with its private half intact, that
  * `canSign` is computed from what was actually stored rather than from what the generator
  * returned, and that the selection is refused at the API boundary rather than deeper down.
  */
object JwksApiSpec extends CentralApiSpec:

  private val jwksPath = "/configuration/jwks"
  private val keysPath = "/configuration/jwks/keys"
  private val generatePath = "/configuration/jwks/generate"
  private val settingsPath = "/configuration/challenges/challenge-settings"

  /** Private RSA/EC JWK parameters (RFC 7517 §4) the admin listing must never carry. */
  private val privateKeyMembers = Set("d", "p", "q", "dp", "dq", "qi")

  private def generate(central: CentralApi, alg: String): Task[(Status, String)] =
    central.postEmpty(generatePath, "alg" -> alg).flatMap: result =>
      if result.status == Status.Created then result.stringAt("kid").map(result.status -> _)
      else ZIO.succeed(result.status -> "")

  private def summaries(central: CentralApi): Task[Chunk[Json.Obj]] =
    central.get(keysPath).flatMap(_.items("keys"))

  private def summary(central: CentralApi, kid: String): Task[Option[Json.Obj]] =
    summaries(central).map(_.find(_.str("kid").contains(kid)))

  /** Challenge settings naming `signingKeyId`, with every other field at a test-safe default.
    * The endpoint replaces the whole record, so the tenant it is sent for is created here too.
    */
  private def settings(tenantId: String, signingKeyId: Option[String]): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "allowedPrefixes" -> Json.Arr(),
      "submissionLimits" -> Json.Obj(
        "otpRequest" -> Json.Arr(),
        "otpSubmit" -> Json.Arr(),
        "passwordSubmit" -> Json.Arr(),
        "passkeyAssertion" -> Json.Arr(),
        "banDurationSeconds" -> Json.Num(0),
      ),
      "otpLength" -> Json.Num(6),
      "otpResendAfter" -> Json.Num(60),
      "passkeySettings" -> Json.Obj(
        "rpId" -> Json.Str("localhost"),
        "rpName" -> Json.Str("Versola"),
        "origins" -> Json.Arr(Json.Str("http://localhost:3000")),
        "userVerification" -> Json.Str("preferred"),
      ),
      "ipHeader" -> Json.Str("X-Forwarded-For"),
      "signingKeyId" -> signingKeyId.fold(Json.Null)(Json.Str(_)),
    )

  private def readSigningKeyId(central: CentralApi, tenantId: String): Task[Option[String]] =
    central.get(settingsPath, "tenantId" -> tenantId)
      .flatMap(_.obj)
      .map(_.obj("settings").flatMap(_.str("signingKeyId")))

  def spec = suite("Central API: JWKS")(
    test("a generated PS256 key is stored with both halves and reported as signable") {
      for
        central <- api
        (status, kid) <- generate(central, "PS256")
        stored <- eventually(summary(central, kid))(_.isDefined)
        _ <- central.delete(jwksPath, "kid" -> kid)
      yield assertTrue(status == Status.Created) &&
        assertTrue(stored.exists(_.bool("canSign").contains(true)))
          .label(s"central generated both halves of '$kid', so it must be able to sign with it") &&
        assertTrue(stored.exists(_.str("algorithm").contains("PS256")))
          .label("the summary's algorithm must be the one the key was generated for") &&
        assertTrue(stored.exists(_.str("keyType").contains("RSA")))
          .label("PS256 is RSASSA-PSS, so the key type must be RSA")
    },
    test("a generated ES256 key names its curve") {
      for
        central <- api
        (status, kid) <- generate(central, "ES256")
        stored <- eventually(summary(central, kid))(_.isDefined)
        _ <- central.delete(jwksPath, "kid" -> kid)
      yield assertTrue(status == Status.Created) &&
        assertTrue(stored.exists(_.str("keyType").contains("EC")))
          .label("ES256 is ECDSA, so the key type must be EC") &&
        assertTrue(stored.exists(_.str("curve").contains("P-256")))
          .label("ES256 is defined over P-256 only (RFC 7518 §3.4), so the curve must say so") &&
        assertTrue(stored.exists(_.bool("canSign").contains(true)))
    },
    test("a generated key is published in the admin JWKS without its private half") {
      for
        central <- api
        (_, kid) <- generate(central, "PS256")
        published <- eventually(central.get(jwksPath).flatMap(_.items("keys")))(_.exists(_.str("kid").contains(kid)))
        generated = published.filter(_.str("kid").contains(kid))
        leaked = generated.flatMap(_.fields.collect { case (name, value) if privateKeyMembers(name) && value != Json.Null => name })
        _ <- central.delete(jwksPath, "kid" -> kid)
      yield assertTrue(generated.nonEmpty)
        .label(s"the generated key '$kid' must appear in the published set") &&
        assertTrue(leaked.isEmpty)
          .label(s"the published JWK leaks private parameters ${leaked.mkString(", ")}") &&
        assertTrue(generated.forall(key => List("kty", "use", "kid", "alg").forall(key.has)))
          .label("a generated JWK must be published with kty/use/kid/alg")
    },
    test("an unsupported alg is rejected and generates nothing") {
      for
        central <- api
        before <- summaries(central).map(_.size)
        rejected <- central.postEmpty(generatePath, "alg" -> "HS256")
        unknown <- central.postEmpty(generatePath, "alg" -> "not-an-alg")
        after <- summaries(central).map(_.size)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("HS256 is a shared secret, not a JWKS keypair") &&
        assertTrue(unknown.status == Status.BadRequest) &&
        assertTrue(before == after)
          .label("a rejected generation must not leave a key behind")
    },
    test("generating without the alg parameter is rejected") {
      for
        central <- api
        rejected <- central.postEmpty(generatePath)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("alg is a required query parameter")
    },
    test("a tenant can be pointed at a signable key, and read back pointing at it") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-signing")
        // The tenant first: a tenant created while a signable key exists is given that key
        // by default, which would make the selection below indistinguishable from a no-op.
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        (_, kid) <- generate(central, "PS256")
        selected <- central.put(settingsPath, settings(tenantId, Some(kid)))
        readBack <- eventually(readSigningKeyId(central, tenantId))(_.contains(kid))
        _ <- central.put(settingsPath, settings(tenantId, None))
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
        _ <- central.delete(jwksPath, "kid" -> kid)
      yield assertTrue(selected.status == Status.NoContent) &&
        assertTrue(readBack.contains(kid))
          .label("auth reads this selection through the same record, so it must be readable back")
    },
    // A tenant pointing at a key nothing can sign with would not fail here and would not
    // fail at sign time either -- auth would fall back to its own configured key, issuing
    // tokens under an algorithm nobody chose. The write is the only place to catch it.
    test("a kid central holds no private half for is refused") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-signing")
        verifyOnly <- summaries(central).map(_.find(_.bool("canSign").contains(false)).flatMap(_.str("kid")))
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        rejected <- ZIO.foreach(verifyOnly)(kid => central.put(settingsPath, settings(tenantId, Some(kid))))
        stored <- readSigningKeyId(central, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(verifyOnly.isDefined)
        .label("this deployment is seeded from bootstrap.jwks, so a verify-only key must exist") &&
        assertTrue(rejected.forall(_.status == Status.BadRequest))
          .label(s"a verify-only kid must be refused, got ${rejected.map(_.status)}") &&
        assertTrue(stored != verifyOnly)
          .label("the refused write must not have landed")
    },
    test("a kid that does not exist is refused") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-signing")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        rejected <- central.put(settingsPath, settings(tenantId, Some("no-such-kid")))
        stored <- readSigningKeyId(central, tenantId)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(!stored.contains("no-such-kid"))
          .label("the refused write must not have landed")
    },
    // Deleting the key a tenant signs with would leave that tenant silently falling back to
    // auth's own key, and every token already issued under it unverifiable.
    test("a key a tenant still signs with cannot be deleted") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-signing")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        (_, kid) <- generate(central, "PS256")
        _ <- central.put(settingsPath, settings(tenantId, Some(kid)))
        _ <- eventually(readSigningKeyId(central, tenantId))(_.contains(kid))
        refused <- central.delete(jwksPath, "kid" -> kid)
        stillThere <- summary(central, kid)
        // Released, then deleted: the order an operator has to follow.
        _ <- central.put(settingsPath, settings(tenantId, None))
        _ <- eventually(readSigningKeyId(central, tenantId))(_.isEmpty)
        deleted <- central.delete(jwksPath, "kid" -> kid)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(refused.status == Status.Conflict)
        .label(s"deleting an in-use key must be refused, got ${refused.status}") &&
        assertTrue(refused.body.contains(tenantId))
          .label(s"the refusal must name the tenant blocking it, got: ${refused.body}") &&
        assertTrue(stillThere.isDefined)
          .label("the refused delete must not have removed the key") &&
        assertTrue(deleted.status == Status.NoContent)
          .label("once no tenant points at it, the same key must delete cleanly")
    },
    test("an anonymous caller can neither list nor generate keys") {
      for
        central <- api
        listed <- central.anonymous.get(keysPath)
        published <- central.anonymous.get(jwksPath)
        generated <- central.anonymous.postEmpty(generatePath, "alg" -> "PS256")
      yield assertTrue(listed.status == Status.Unauthorized) &&
        assertTrue(published.status == Status.Unauthorized) &&
        assertTrue(generated.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
