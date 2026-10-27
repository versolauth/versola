package versola.central.configuration.edges

import versola.util.http.{AccessLogPolicy, InboundPolicy, OverloadPolicy, RateLimitDimension, RateLimitPolicies, RateLimitPolicy, RateLimitWindow}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import versola.util.http.GuardMode
import versola.util.http.RateLimitScope

/** The rules of `PATCH /configuration/edges/policies`, with no database and no HTTP: reading the body, and what a patch makes of a record. */
object EdgePoliciesPatchSpec extends ZIOSpecDefault:

  private val t1 = Instant.parse("2026-10-27T10:00:00Z")
  private val t2 = Instant.parse("2026-10-27T11:00:00Z")

  private val record = EdgeRecord(EdgeId("edge-1"), Json.Obj(), None, requireDpopNonce = true, overloadPolicy = None, accessLogPolicy = None, rateLimitPolicies = None, policyMeta = None, groupId = EdgeGroup.DefaultId, groupKind = EdgeGroupKind.FirstParty)

  private val perClient = RateLimitPolicy(
    name = "per-client",
    by = List(RateLimitDimension.Client),
    limits = List(RateLimitWindow(limit = 100, windowSeconds = 60)),
    burst = 5,
    scope = RateLimitScope.EdgeWide,
    cost = 1,
    mode = GuardMode.Shadow,
  )
  private val sets = RateLimitPolicies(List(perClient), replicas = 1)
  private val overload = OverloadPolicy(inbound = Some(InboundPolicy(maxInFlight = Some(64), mode = None, continuationShare = None, startShare = None, retryAfterSeconds = None, adaptive = None)), outbound = None)

  private def parse(json: String) = json.fromJson[Json.Obj].left.map(PolicyPatchError.UnknownSection(_)).flatMap(EdgePoliciesPatch.parse)

  def spec = suite("EdgePoliciesPatch")(
    suite("reading the body")(
      test("absent is kept, null removes, an object replaces; expectedRevisions are read by section") {
        val patch = parse(s"""{"overload":null,"accessLog":{"mode":"full"},"expectedRevisions":{"overload":3,"rateLimits":0}}""")
        assertTrue(
          patch == Right(
            EdgePoliciesPatch(
              overload = Change.Remove,
              accessLog = Change.Replace(AccessLogPolicy(mode = Some("full"), slowMillis = None, successSampleRate = None)),
              rateLimits = Change.Keep,
              expectedRevisions = Map(PolicySection.Overload -> 3, PolicySection.RateLimits -> 0),
            ),
          ),
        )
      },
      test("an empty body keeps everything") {
        assertTrue(parse("{}") == Right(EdgePoliciesPatch()))
      },
      test("a key the API does not know is refused, whether a section or a revision") {
        val section = parse("""{"overlod":{}}""")
        val revision = parse("""{"expectedRevisions":{"overlod":1}}""")
        assertTrue(
          section.left.exists(_.message.contains("unknown section 'overlod'")),
          revision.left.exists(_.message.contains("unknown section 'overlod'")),
        )
      },
      test("a section of the wrong shape names the section") {
        val bad = parse("""{"rateLimits":{"policies":[{"name":"x","by":["ip-address"],"limits":[{"limit":1,"windowSeconds":1}]}]}}""")
        val number = parse("""{"expectedRevisions":{"overload":"three"}}""")
        assertTrue(
          bad.left.exists { case PolicyPatchError.Malformed(section, _) => section == PolicySection.RateLimits; case _ => false },
          number.isLeft,
        )
      },
    ),
    suite("applying a patch")(
      test("a replaced section is stored and its revision starts at 1; the others stay unset") {
        val result = EdgePoliciesPatch(record, EdgePoliciesPatch(rateLimits = Change.Replace(sets)), t1)
        assertTrue(
          result.map(_.rateLimitPolicies) == Right(Some(sets)),
          result.map(_.overloadPolicy) == Right(None),
          result.toOption.flatMap(_.policyMeta).exists(m => m == Map("rateLimits" -> PolicySectionMeta(1, t1))),
        )
      },
      test("a section the patch does not name keeps its value, its revision and its time") {
        val first = EdgePoliciesPatch(record, EdgePoliciesPatch(overload = Change.Replace(overload)), t1).toOption.get
        val second = EdgePoliciesPatch(first, EdgePoliciesPatch(rateLimits = Change.Replace(sets)), t2).toOption.get
        assertTrue(
          second.overloadPolicy.contains(overload),
          second.policyMeta == Some(Map("overload" -> PolicySectionMeta(1, t1), "rateLimits" -> PolicySectionMeta(1, t2))),
        )
      },
      test("a change that changes nothing does not move the revision or the time") {
        val first = EdgePoliciesPatch(record, EdgePoliciesPatch(overload = Change.Replace(overload)), t1).toOption.get
        val again = EdgePoliciesPatch(first, EdgePoliciesPatch(overload = Change.Replace(overload)), t2)
        assertTrue(again.map(_.policyMeta) == Right(first.policyMeta))
      },
      test("an empty section is the same as removing it, and removing is a change") {
        val first = EdgePoliciesPatch(record, EdgePoliciesPatch(overload = Change.Replace(overload), rateLimits = Change.Replace(sets)), t1).toOption.get
        val emptied = EdgePoliciesPatch(first, EdgePoliciesPatch(overload = Change.Replace(OverloadPolicy.empty), rateLimits = Change.Replace(RateLimitPolicies.empty)), t2)
        val removed = EdgePoliciesPatch(first, EdgePoliciesPatch(overload = Change.Remove), t2)
        assertTrue(
          emptied.map(r => (r.overloadPolicy, r.rateLimitPolicies)) == Right((None, None)),
          emptied.toOption.flatMap(_.policyMeta).exists(m => m("overload").revision == 2 && m("rateLimits").revision == 2),
          removed.map(_.overloadPolicy) == Right(None),
          removed.map(_.rateLimitPolicies) == Right(Some(sets)),
        )
      },
      test("an invalid section names the section and the rule, and changes nothing") {
        val tooBig = sets.copy(policies = List(perClient.copy(burst = 500)))
        val result = EdgePoliciesPatch(record, EdgePoliciesPatch(overload = Change.Replace(overload), rateLimits = Change.Replace(tooBig)), t1)
        assertTrue(
          result.left.exists { case PolicyPatchError.Invalid(section, detail) => section == PolicySection.RateLimits && detail.contains("burst"); case _ => false },
        )
      },
      test("an expected revision that matches lets the patch through; one that does not is a conflict carrying the current record") {
        val first = EdgePoliciesPatch(record, EdgePoliciesPatch(overload = Change.Replace(overload)), t1).toOption.get
        val ok = EdgePoliciesPatch(first, EdgePoliciesPatch(overload = Change.Remove, expectedRevisions = Map(PolicySection.Overload -> 1)), t2)
        val stale = EdgePoliciesPatch(first, EdgePoliciesPatch(overload = Change.Remove, expectedRevisions = Map(PolicySection.Overload -> 0)), t2)
        val neverSet = EdgePoliciesPatch(record, EdgePoliciesPatch(accessLog = Change.Replace(AccessLogPolicy(mode = Some("full"), slowMillis = None, successSampleRate = None)), expectedRevisions = Map(PolicySection.AccessLog -> 0)), t1)
        assertTrue(
          ok.map(_.overloadPolicy) == Right(None),
          stale == Left(PolicyPatchError.Conflict(PolicySection.Overload, first)),
          neverSet.isRight,
        )
      },
      test("a precondition on a section the patch does not touch is still checked") {
        val first = EdgePoliciesPatch(record, EdgePoliciesPatch(overload = Change.Replace(overload)), t1).toOption.get
        val result = EdgePoliciesPatch(first, EdgePoliciesPatch(rateLimits = Change.Replace(sets), expectedRevisions = Map(PolicySection.Overload -> 0)), t2)
        assertTrue(result.left.exists { case PolicyPatchError.Conflict(section, _) => section == PolicySection.Overload; case _ => false })
      },
    ),
  )
