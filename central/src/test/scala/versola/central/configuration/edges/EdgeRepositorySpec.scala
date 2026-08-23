package versola.central.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.json.ast.Json
import zio.{Scope, ZIO}
import zio.test.*

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
          _ <- env.repository.createEdge(edgeId, sampleJwk)
          found <- env.repository.find(edgeId)
        yield assertTrue(
          found.isDefined,
          found.get.id == edgeId,
          found.get.oldPublicKey.isEmpty,
        )
      },
      test("getAll returns all edges") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk)
          _ <- env.repository.createEdge(edge2Id, sampleJwk)
          all <- env.repository.getAll
        yield assertTrue(
          all.length == 2,
          all.map(_.id).toSet == Set(edgeId, edge2Id),
        )
      },
      test("rotateEdgeKey updates key and stores old key") {
        for
          _ <- env.repository.createEdge(edgeId, sampleJwk)
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
          _ <- env.repository.createEdge(edgeId, sampleJwk)
          before <- env.repository.find(edgeId)
          _ <- env.repository.deleteEdge(edgeId)
          after <- env.repository.find(edgeId)
        yield assertTrue(
          before.isDefined,
          after.isEmpty,
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
          _ <- env.repository.createEdge(edgeId, sampleJwk)
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
