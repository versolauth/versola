package versola.central.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import versola.util.http.{AdaptivePolicy, BreakerPolicy, InboundPolicy, OutboundPolicy, OverloadPolicy}
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
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          found <- env.repository.find(edgeId)
        yield assertTrue(
          found.isDefined,
          found.get.id == edgeId,
          found.get.oldPublicKey.isEmpty,
        )
      },
      test("getAll returns all edges") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          _ <- env.repository.createEdge(edge2Id, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          all <- env.repository.getAll
        yield assertTrue(
          all.length == 2,
          all.map(_.id).toSet == Set(edgeId, edge2Id),
        )
      },
      test("rotateEdgeKey updates key and stores old key") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
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
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
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
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          found <- env.repository.find(edgeId)
        yield assertTrue(found.exists(_.requireDpopNonce))
      },
      test("a new edge has no overload policy, setOverloadPolicy stores one for the named edge only, and None clears it") {
        val policy = OverloadPolicy(
          inbound = Some(InboundPolicy(
            mode = Some("enforce"),
            maxInFlight = Some(128),
            adaptive = Some(AdaptivePolicy(enabled = Some(true), minLimit = Some(16))),
          )),
          outbound =
            Some(OutboundPolicy(requestTimeoutMillis = Some(2500), breaker = Some(BreakerPolicy(enabled = Some(true), openSeconds = Some(20))))),
        )
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          _ <- env.repository.createEdge(edge2Id, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          fresh <- env.repository.find(edgeId)
          _ <- env.repository.setOverloadPolicy(edgeId, Some(policy))
          set <- env.repository.find(edgeId)
          other <- env.repository.find(edge2Id)
          all <- env.repository.getAll
          _ <- env.repository.setOverloadPolicy(edgeId, None)
          cleared <- env.repository.find(edgeId)
        yield assertTrue(
          fresh.exists(_.overloadPolicy.isEmpty),
          set.flatMap(_.overloadPolicy) == Some(policy),
          other.exists(_.overloadPolicy.isEmpty),
          all.find(_.id == edgeId).flatMap(_.overloadPolicy) == Some(policy),
          cleared.exists(_.overloadPolicy.isEmpty),
        )
      },
      test("setRequireDpopNonce changes only the named edge") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
          _ <- env.repository.createEdge(edge2Id, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
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
          _ <- env.repository.createEdge(edgeId, sampleJwk, EdgeRecord.DefaultRequireDpopNonce)
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
