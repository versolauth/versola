package versola.util.http

import zio.json.*
import zio.test.*

object RateLimitPolicySpec extends ZIOSpecDefault:

  private val valid = RateLimitPolicy(
    name = "per-client",
    by = List(RateLimitDimension.Client),
    limits = List(RateLimitWindow(limit = 100, windowSeconds = 10), RateLimitWindow(limit = 1000, windowSeconds = 60)),
    burst = 20,
    cost = 2,
    mode = GuardMode.Enforce,
    scope = RateLimitScope.EdgeWide,
  )

  private def invalid(policy: RateLimitPolicy, fragment: String) =
    val result = policy.validate
    assertTrue(result.isLeft, result.left.exists(_.contains(fragment)))

  def spec = suite("RateLimitPolicy")(
    suite("wire form")(
      test("round-trips with every field") {
        val json = valid.toJson
        assertTrue(json.fromJson[RateLimitPolicy] == Right(valid))
      },
      test("every field is required: nothing has an implicit value") {
        val full = Map(
          "name" -> "\"all\"",
          "by" -> "[]",
          "scope" -> "\"edge-wide\"",
          "limits" -> "[{\"limit\":5,\"windowSeconds\":1}]",
          "burst" -> "1",
          "cost" -> "1",
          "mode" -> "\"shadow\"",
        )
        def body(without: Option[String]) = full.removedAll(without).map((k, v) => s"\"$k\":$v").mkString("{", ",", "}")
        assertTrue(
          body(None).fromJson[RateLimitPolicy].isRight,
          full.keys.forall(k => body(Some(k)).fromJson[RateLimitPolicy].left.exists(_.contains(k))),
          """{"policies":[]}""".fromJson[RateLimitPolicies].left.exists(_.contains("replicas")),
        )
      },
      test("the strings are the documented ones") {
        val parsed =
          """{"name":"a","by":["ip","client","subject","session","jkt"],"scope":"endpoint","mode":"off","burst":1,"cost":1,"limits":[{"limit":1,"windowSeconds":1}]}"""
            .fromJson[RateLimitPolicy]
        assertTrue(
          parsed.map(_.by.map(_.wire)) == Right(List("ip", "client", "subject", "session", "jkt")),
          parsed.map(_.scope) == Right(RateLimitScope.Endpoint),
          parsed.map(_.mode) == Right(GuardMode.Off),
        )
      },
      test("an unknown dimension, scope or mode is refused with a message that names the choices") {
        val dim =
          """{"name":"a","by":["user"],"scope":"edge-wide","burst":1,"cost":1,"mode":"shadow","limits":[{"limit":1,"windowSeconds":1}]}""".fromJson[
            RateLimitPolicy,
          ]
        val scope = """{"name":"a","by":[],"scope":"pod","burst":1,"cost":1,"mode":"shadow","limits":[{"limit":1,"windowSeconds":1}]}""".fromJson[
          RateLimitPolicy,
        ]
        val mode = """{"name":"a","by":[],"scope":"edge-wide","burst":1,"cost":1,"mode":"loud","limits":[{"limit":1,"windowSeconds":1}]}""".fromJson[
          RateLimitPolicy,
        ]
        assertTrue(
          dim.left.exists(_.contains("dimension must be one of ip, client, subject, session, jkt")),
          scope.left.exists(_.contains("scope must be one of edge-wide, resource, endpoint")),
          mode.left.exists(_.contains("mode must be off, shadow or enforce")),
        )
      },
    ),
    suite("validate")(
      test("accepts a well-formed policy") {
        assertTrue(valid.validate == Right(valid))
      },
      test("refuses a name outside the metric-label charset") {
        val bad = List("", "Upper", "9lives", "has space", "dot.ted", "slash/y", "x" * 49)
        assertTrue(bad.forall(n => valid.copy(name = n).validate.isLeft), valid.copy(name = "ok_name-1").validate.isRight)
      },
      test("refuses a repeated dimension") {
        invalid(valid.copy(by = List(RateLimitDimension.Client, RateLimitDimension.Client)), "must not repeat")
      },
      test("refuses no windows, too many, or a repeated window") {
        val many = (1 to 9).map(i => RateLimitWindow(10, i)).toList
        assertTrue(valid.copy(limits = Nil).validate.isLeft) &&
        invalid(valid.copy(limits = many), "at most 8 windows") &&
        invalid(valid.copy(limits = List(RateLimitWindow(10, 5), RateLimitWindow(20, 5))), "same window")
      },
      test("refuses a limit or a window out of range") {
        invalid(valid.copy(limits = List(RateLimitWindow(0, 10))), "limit must be between") &&
        invalid(valid.copy(limits = List(RateLimitWindow(10, 0))), "windowSeconds must be between") &&
        invalid(valid.copy(limits = List(RateLimitWindow(10, 86_401))), "windowSeconds must be between")
      },
      test("refuses a burst above the smallest limit") {
        invalid(valid.copy(burst = 101), "burst must not exceed the smallest limit") &&
        invalid(valid.copy(burst = 0), "burst must be between")
      },
      test("refuses a cost above the burst, which could never pass") {
        invalid(valid.copy(burst = 2, cost = 3), "cost must not exceed burst") &&
        invalid(valid.copy(cost = 0), "cost must be between")
      },
    ),
    suite("validateAll")(
      test("refuses two policies with one name") {
        val result = RateLimitPolicy.validateAll(List(valid, valid))
        assertTrue(result.left.exists(_.contains("same name")))
      },
      test("refuses more than the maximum, and reports the first bad policy") {
        val many = (1 to 65).map(i => valid.copy(name = s"p$i")).toList
        val bad = RateLimitPolicy.validateAll(List(valid, valid.copy(name = "Bad")))
        assertTrue(RateLimitPolicy.validateAll(many).isLeft, bad.isLeft, RateLimitPolicy.validateAll(many.take(64)).isRight)
      },
    ),
    suite("checkpoint")(
      test("follows from the scope and what the policy is keyed on") {
        assertTrue(
          valid.checkpoint == Checkpoint.Identity,
          valid.copy(by = List(RateLimitDimension.Ip)).checkpoint == Checkpoint.Address,
          valid.copy(scope = RateLimitScope.Resource).checkpoint == Checkpoint.Route,
          valid.copy(scope = RateLimitScope.Endpoint, by = List(RateLimitDimension.Subject)).checkpoint == Checkpoint.Route,
        )
      },
    ),
    suite("address policies (R1)")(
      test("ip or certificate alone, edge-wide, is valid; anything wider is refused with the reason") {
        val ip = valid.copy(name = "per-ip", by = List(RateLimitDimension.Ip))
        val cert = valid.copy(name = "per-cert", by = List(RateLimitDimension.Certificate))
        assertTrue(
          ip.validate.isRight,
          cert.validate.isRight,
          ip.checkpoint == Checkpoint.Address,
          cert.checkpoint == Checkpoint.Address,
          ip.copy(by = List(RateLimitDimension.Ip, RateLimitDimension.Client)).validate.left.exists(_.contains("before the token is read")),
          ip.copy(scope = RateLimitScope.Endpoint).validate.left.exists(_.contains("edge-wide")),
          cert.copy(by = List(RateLimitDimension.Certificate, RateLimitDimension.Ip)).validate.isLeft,
        )
      },
      test("the certificate dimension has its wire name") {
        assertTrue(RateLimitDimension.parse("certificate") == Some(RateLimitDimension.Certificate))
      },
    ),
    suite("replicas")(
      test("default 1, validated between 1 and 1000, round-trips") {
        import zio.json.*
        val set = RateLimitPolicies(List(valid), replicas = 3)
        assertTrue(
          RateLimitPolicies(policies = Nil, replicas = 1).replicas == 1,
          set.validate.isRight,
          RateLimitPolicies(List(valid), replicas = 0).validate.left.exists(_.contains("replicas")),
          RateLimitPolicies(List(valid), replicas = 1001).validate.isLeft,
          set.toJson.fromJson[RateLimitPolicies] == Right(set),
          """{"policies":[],"replicas":1}""".fromJson[RateLimitPolicies] == Right(RateLimitPolicies.empty),
        )
      },
    ),
    suite("recommended set")(
      test("validates, has unique names and starts every policy in shadow") {
        val set = RateLimitPolicies.recommended
        assertTrue(
          set.validate.isRight,
          set.policies.map(_.name) == List("per-client", "per-user", "per-user-endpoint", "per-address", "per-certificate"),
          set.policies.forall(_.mode == GuardMode.Shadow),
          // R2 for the first two, R3 for the endpoint one, R1 for the address and the certificate
          set.policies.map(_.checkpoint) == List(Checkpoint.Identity, Checkpoint.Identity, Checkpoint.Route, Checkpoint.Address, Checkpoint.Address),
        )
      },
      test("survives the wire") {
        import zio.json.*
        assertTrue(RateLimitPolicies.recommended.toJson.fromJson[RateLimitPolicies] == Right(RateLimitPolicies.recommended))
      },
    ),
  )
