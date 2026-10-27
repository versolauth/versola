package versola.central.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import versola.util.http.{
  AccessLogPolicy,
  AdaptivePolicy,
  BreakerPolicy,
  GuardMode,
  InboundPolicy,
  OutboundPolicy,
  OverloadPolicy,
  RateLimitDimension,
  RateLimitPolicies,
  RateLimitPolicy,
  RateLimitScope,
  RateLimitWindow,
}
import zio.json.ast.Json
import zio.test.*
import zio.{Scope, ZIO}

trait EdgeRepositorySpec extends DatabaseSpecBase[EdgeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val edgeId = EdgeId("test-edge")
  val edge2Id = EdgeId("edge-2")
  val tenant1 = TenantId("tenant-a")
  val tenant2 = TenantId("tenant-b")

  // Sample JWK for testing
  val sampleJwk = Json.Obj(
    "kty" -> Json.Str("RSA"),
    "kid" -> Json.Str("2026-04-29_18-30-00"),
    "alg" -> Json.Str("RS256"),
    "use" -> Json.Str("sig"),
    "n" -> Json.Str("sample-modulus"),
    "e" -> Json.Str("AQAB"),
  )

  val newJwk = Json.Obj(
    "kty" -> Json.Str("RSA"),
    "kid" -> Json.Str("2026-04-29_19-00-00"),
    "alg" -> Json.Str("RS256"),
    "use" -> Json.Str("sig"),
    "n" -> Json.Str("new-modulus"),
    "e" -> Json.Str("AQAB"),
  )

  override def testCases(env: EdgeRepositorySpec.Env) =
    List(
      test("create and find edge") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          found <- env.repository.find(edgeId)
        yield assertTrue(
          found.isDefined,
          found.get.id == edgeId,
          found.get.oldPublicKey.isEmpty,
        )
      },
      test("getAll returns all edges") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          _ <- env.repository.createEdge(edge2Id, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          all <- env.repository.getAll
        yield assertTrue(
          all.length == 2,
          all.map(_.id).toSet == Set(edgeId, edge2Id),
        )
      },
      test("rotateEdgeKey updates key and stores old key") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          beforeRotation <- env.repository.find(edgeId)
          _ <- env.repository.rotateEdgeKey(edgeId, newJwk)
          afterRotation <- env.repository.find(edgeId)
        yield assertTrue(
          beforeRotation.get.oldPublicKey.isEmpty,
          afterRotation.get.publicKey == newJwk,
          afterRotation.get.oldPublicKey.contains(sampleJwk),
        )
      },
      test("deleteEdge removes edge") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          before <- env.repository.find(edgeId)
          _ <- env.repository.deleteEdge(edgeId)
          after <- env.repository.find(edgeId)
        yield assertTrue(
          before.isDefined,
          after.isEmpty,
        )
      },
      // RFC 9449 §9 was unconditional at every edge before it became a setting, so a newly
      // registered edge that did not demand a nonce would be a downgrade nobody asked for.
      test("a newly registered edge requires a DPoP nonce") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          found <- env.repository.find(edgeId)
        yield assertTrue(found.exists(_.requireDpopNonce))
      },
      test("patchPolicies stores sections for the named edge only, keeps the others, counts revisions, and None clears") {
        val overload = OverloadPolicy(
          inbound = Some(InboundPolicy(
            maxInFlight = Some(64),
            mode = None,
            continuationShare = None,
            startShare = None,
            retryAfterSeconds = None,
            adaptive = None,
          )),
          outbound = None,
        )
        val log = AccessLogPolicy(mode = Some("sampled"), slowMillis = Some(250), successSampleRate = Some(0.2))
        val limits = RateLimitPolicies(
          List(
            RateLimitPolicy(
              name = "per-client",
              by = List(RateLimitDimension.Client),
              limits = List(RateLimitWindow(100, 60)),
              burst = 5,
              mode = GuardMode.Enforce,
              scope = RateLimitScope.EdgeWide,
              cost = 1,
            ),
            RateLimitPolicy(
              name = "per-endpoint",
              scope = RateLimitScope.Endpoint,
              limits = List(RateLimitWindow(10, 1), RateLimitWindow(500, 3600)),
              by = Nil,
              burst = 1,
              cost = 1,
              mode = GuardMode.Shadow,
            ),
          ),
          replicas = 3,
        )
        val at = java.time.Instant.parse("2026-10-27T10:00:00Z")
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          _ <- env.repository.createEdge(edge2Id, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          fresh <- env.repository.find(edgeId)
          _ <- env.repository.patchPolicies(
            edgeId,
            EdgePoliciesPatch(_, EdgePoliciesPatch(overload = Change.Replace(overload), accessLog = Change.Replace(log)), at),
          )
          _ <- env.repository.patchPolicies(edgeId, EdgePoliciesPatch(_, EdgePoliciesPatch(rateLimits = Change.Replace(limits)), at.plusSeconds(60)))
          set <- env.repository.find(edgeId)
          other <- env.repository.find(edge2Id)
          all <- env.repository.getAll
          _ <- env.repository.patchPolicies(edgeId, EdgePoliciesPatch(_, EdgePoliciesPatch(overload = Change.Remove), at.plusSeconds(120)))
          cleared <- env.repository.find(edgeId)
        yield assertTrue(
          fresh.exists(r => r.overloadPolicy.isEmpty && r.accessLogPolicy.isEmpty && r.rateLimitPolicies.isEmpty && r.policyMeta.isEmpty),
          set.flatMap(_.overloadPolicy) == Some(overload),
          set.flatMap(_.accessLogPolicy) == Some(log),
          set.flatMap(_.rateLimitPolicies) == Some(limits),
          set.flatMap(_.policyMeta) == Some(
            Map(
              "overload" -> PolicySectionMeta(1, at),
              "accessLog" -> PolicySectionMeta(1, at),
              "rateLimits" -> PolicySectionMeta(1, at.plusSeconds(60)),
            ),
          ),
          other.exists(r => r.overloadPolicy.isEmpty && r.rateLimitPolicies.isEmpty && r.policyMeta.isEmpty),
          all.find(_.id == edgeId).flatMap(_.rateLimitPolicies) == Some(limits),
          cleared.exists(_.overloadPolicy.isEmpty),
          cleared.flatMap(_.policyMeta).exists(_("overload").revision == 2),
          cleared.flatMap(_.accessLogPolicy) == Some(log),
        )
      },
      test("a refused change writes nothing, and a missing edge is None") {
        val overload = OverloadPolicy(
          inbound = Some(InboundPolicy(
            maxInFlight = Some(64),
            mode = None,
            continuationShare = None,
            startShare = None,
            retryAfterSeconds = None,
            adaptive = None,
          )),
          outbound = None,
        )
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          refused <- env.repository.patchPolicies(edgeId, record => Left(PolicyPatchError.Conflict(PolicySection.Overload, record)))
          after <- env.repository.find(edgeId)
          missing <- env.repository.patchPolicies(EdgeId("edge-nobody"), record => Right(record))
          stored <- env.repository.patchPolicies(
            edgeId,
            EdgePoliciesPatch(_, EdgePoliciesPatch(overload = Change.Replace(overload)), java.time.Instant.EPOCH),
          )
        yield assertTrue(
          refused.exists(_.isLeft),
          after.exists(_.overloadPolicy.isEmpty),
          missing.isEmpty,
          stored.exists(_.isRight),
        )
      },
      test("two patches of one edge at once are applied one after the other: no revision is lost") {
        val bump = (n: Int) =>
          env.repository.patchPolicies(
            edgeId,
            EdgePoliciesPatch(
              _,
              EdgePoliciesPatch(overload =
                Change.Replace(OverloadPolicy(
                  inbound = Some(InboundPolicy(
                    maxInFlight = Some(n),
                    mode = None,
                    continuationShare = None,
                    startShare = None,
                    retryAfterSeconds = None,
                    adaptive = None,
                  )),
                  outbound = None,
                )),
              ),
              java.time.Instant.EPOCH,
            ),
          )
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          _ <- ZIO.foreachParDiscard(1 to 12)(n => bump(n + 10))
          after <- env.repository.find(edgeId)
        yield assertTrue(after.flatMap(_.policyMeta).exists(_("overload").revision == 12))
      },
      test("setRequireDpopNonce changes only the named edge") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          _ <- env.repository.createEdge(edge2Id, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          _ <- env.repository.setRequireDpopNonce(edgeId, false)
          changed <- env.repository.find(edgeId)
          untouched <- env.repository.find(edge2Id)
          _ <- env.repository.setRequireDpopNonce(edgeId, true)
          restored <- env.repository.find(edgeId)
        yield assertTrue(
          changed.exists(!_.requireDpopNonce),
          untouched.exists(_.requireDpopNonce),
          restored.exists(_.requireDpopNonce),
        )
      },
      test("find returns None for non-existent edge") {
        for
          result <- env.repository.find(EdgeId("non-existent"))
        yield assertTrue(
          result.isEmpty,
        )
      },
      test("multiple key rotations maintain old key from previous rotation") {
        val thirdJwk = Json.Obj(
          "kty" -> Json.Str("RSA"),
          "kid" -> Json.Str("2026-04-29_20-00-00"),
          "alg" -> Json.Str("RS256"),
          "use" -> Json.Str("sig"),
          "n" -> Json.Str("third-modulus"),
          "e" -> Json.Str("AQAB"),
        )

        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce, EdgeGroup.DefaultId)
          _ <- env.repository.rotateEdgeKey(edgeId, newJwk)
          afterFirst <- env.repository.find(edgeId)
          _ <- env.repository.rotateEdgeKey(edgeId, thirdJwk)
          afterSecond <- env.repository.find(edgeId)
        yield assertTrue(
          afterFirst.get.publicKey == newJwk,
          afterFirst.get.oldPublicKey.contains(sampleJwk),
          afterSecond.get.publicKey == thirdJwk,
          afterSecond.get.oldPublicKey.contains(newJwk), // Old key is now the previous rotation
        )
      },
    )

object EdgeRepositorySpec:
  case class Env(repository: EdgeRepository)
