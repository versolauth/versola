package versola.util.http

import zio.json.*
import zio.test.*

object DeclaredRateLimitSpec extends ZIOSpecDefault:

  private val valid = DeclaredRateLimit(
    limits = List(RateLimitWindow(limit = 60, windowSeconds = 60)),
    burst = 10,
    by = DeclaredRateLimitBy.Subject,
    mode = GuardMode.Enforce,
  )

  private def invalid(limit: DeclaredRateLimit, owner: DeclaredOwner, fragment: String) =
    val result = limit.validate(owner)
    assertTrue(result.isLeft, result.left.exists(_.contains(fragment)))

  def spec = suite("DeclaredRateLimit")(
    suite("wire form")(
      test("round-trips") {
        assertTrue(valid.toJson.fromJson[DeclaredRateLimit] == Right(valid))
      },
      test("defaults: strict spacing, one bucket for the owner, shadow") {
        val parsed = """{"limits":[{"limit":5,"windowSeconds":1}]}""".fromJson[DeclaredRateLimit]
        assertTrue(
          parsed == Right(DeclaredRateLimit(limits = List(RateLimitWindow(5, 1)), burst = 1, by = DeclaredRateLimitBy.None, mode = GuardMode.Shadow)),
          parsed.map(_.by) == Right(DeclaredRateLimitBy.None),
          parsed.map(_.mode) == Right(GuardMode.Shadow),
        )
      },
      test("the strings are the documented ones, the same as a policy's") {
        val parsed = """{"by":"client","mode":"enforce","limits":[{"limit":5,"windowSeconds":1}]}""".fromJson[DeclaredRateLimit]
        val json = valid.toJson
        assertTrue(
          parsed.map(r => (r.by, r.mode)) == Right((DeclaredRateLimitBy.Client, GuardMode.Enforce)),
          json.contains("\"mode\":\"enforce\""),
          json.contains("\"by\":\"subject\""),
        )
      },
      test("an unknown mode is refused with the same message as a policy's") {
        val parsed = """{"mode":"loud","limits":[{"limit":5,"windowSeconds":1}]}""".fromJson[DeclaredRateLimit]
        assertTrue(parsed.left.exists(_.contains("mode must be off, shadow or enforce")))
      },
      test("an unknown key is refused with the choices") {
        val parsed = """{"by":"session","limits":[{"limit":5,"windowSeconds":1}]}""".fromJson[DeclaredRateLimit]
        assertTrue(parsed.left.exists(_.contains("by must be one of subject, client, none")))
      },
    ),
    suite("validate")(
      test("accepts a well-formed declaration on either owner") {
        assertTrue(valid.validate(DeclaredOwner.Endpoint) == Right(valid), valid.validate(DeclaredOwner.Audience) == Right(valid))
      },
      test("applies the rules of a policy: burst, windows, limits") {
        invalid(valid.copy(burst = 61), DeclaredOwner.Endpoint, "burst must not exceed the smallest limit") &&
        invalid(valid.copy(limits = Nil), DeclaredOwner.Endpoint, "limits must have at least one window") &&
        invalid(valid.copy(limits = List(RateLimitWindow(0, 60))), DeclaredOwner.Audience, "limit must be between")
      },
      test("names the fixed policy of the owner in the message") {
        invalid(valid.copy(burst = 0), DeclaredOwner.Endpoint, "resource-endpoint") &&
        invalid(valid.copy(burst = 0), DeclaredOwner.Audience, "resource-client")
      },
      test("by client is refused on a client, where it would mean the same as none") {
        val byClient = valid.copy(by = DeclaredRateLimitBy.Client)
        assertTrue(byClient.validate(DeclaredOwner.Endpoint).isRight) && invalid(byClient, DeclaredOwner.Audience, "by must be subject or none")
      },
    ),
    suite("toPolicy")(
      test("both owners are checked at R3: an endpoint's limit per endpoint template, an audience client's per resource") {
        assertTrue(
          valid.toPolicy(DeclaredOwner.Endpoint).checkpoint == Checkpoint.Route,
          valid.toPolicy(DeclaredOwner.Audience).checkpoint == Checkpoint.Route,
          valid.copy(by = DeclaredRateLimitBy.None).toPolicy(DeclaredOwner.Audience).scope == RateLimitScope.Resource,
          valid.toPolicy(DeclaredOwner.Endpoint).name == "resource-endpoint",
        )
      },
      test("the edge's own policies cannot take the names of the declared ones") {
        val taken = RateLimitPolicy(name = "resource-client", limits = List(RateLimitWindow(1, 1)), by = Nil, scope = RateLimitScope.EdgeWide, burst = 1, cost = 1, mode = GuardMode.Shadow)
        val result = RateLimitPolicy.validateAll(List(taken))
        assertTrue(result.left.exists(_.contains("reserved")))
      },
    ),
  )
