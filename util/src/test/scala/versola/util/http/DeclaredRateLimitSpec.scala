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
          parsed == Right(DeclaredRateLimit(limits = List(RateLimitWindow(5, 1)))),
          parsed.map(_.by) == Right(DeclaredRateLimitBy.None),
          parsed.map(_.mode) == Right(GuardMode.Shadow),
        )
      },
      test("an unknown key is refused with the choices") {
        val parsed = """{"by":"session","limits":[{"limit":5,"windowSeconds":1}]}""".fromJson[DeclaredRateLimit]
        assertTrue(parsed.left.exists(_.contains("by must be one of subject, client, none")))
      },
    ),
    suite("validate")(
      test("accepts a well-formed declaration on either owner") {
        assertTrue(valid.validate(DeclaredOwner.Endpoint) == Right(valid), valid.validate(DeclaredOwner.Client) == Right(valid))
      },
      test("applies the rules of a policy: burst, windows, limits") {
        invalid(valid.copy(burst = 61), DeclaredOwner.Endpoint, "burst must not exceed the smallest limit") &&
        invalid(valid.copy(limits = Nil), DeclaredOwner.Endpoint, "limits must have at least one window") &&
        invalid(valid.copy(limits = List(RateLimitWindow(0, 60))), DeclaredOwner.Client, "limit must be between")
      },
      test("names the fixed policy of the owner in the message") {
        invalid(valid.copy(burst = 0), DeclaredOwner.Endpoint, "resource-endpoint") &&
        invalid(valid.copy(burst = 0), DeclaredOwner.Client, "oauth-client")
      },
      test("by client is refused on a client, where it would mean the same as none") {
        val byClient = valid.copy(by = DeclaredRateLimitBy.Client)
        assertTrue(byClient.validate(DeclaredOwner.Endpoint).isRight) && invalid(byClient, DeclaredOwner.Client, "by must be subject or none")
      },
    ),
    suite("toPolicy")(
      test("an endpoint's limit is scoped to the endpoint template and runs at R3; a client's runs at R2") {
        assertTrue(
          valid.toPolicy(DeclaredOwner.Endpoint).checkpoint == Checkpoint.Route,
          valid.toPolicy(DeclaredOwner.Client).checkpoint == Checkpoint.Identity,
          valid.copy(by = DeclaredRateLimitBy.None).toPolicy(DeclaredOwner.Client).checkpoint == Checkpoint.Identity,
          valid.toPolicy(DeclaredOwner.Endpoint).name == "resource-endpoint",
        )
      },
      test("the edge's own policies cannot take the names of the declared ones") {
        val taken = RateLimitPolicy(name = "oauth-client", limits = List(RateLimitWindow(1, 1)))
        val result = RateLimitPolicy.validateAll(List(taken))
        assertTrue(result.left.exists(_.contains("reserved")))
      },
    ),
  )
