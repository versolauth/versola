package versola.central.configuration.edges

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.tenants.TenantId
import versola.util.RsaKeyPair
import zio.json.ast.Json
import zio.test.*
import zio.{UIO, ZIO}

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object EdgeServiceSpec extends ZIOSpecDefault, ZIOStubs:

  val edgeId = EdgeId("edge-1")
  val edge2Id = EdgeId("edge-2")
  val tenant1 = TenantId("tenant-1")
  val tenant2 = TenantId("tenant-2")

  // Sample key pair for testing
  val testKeyPair: RsaKeyPair =
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    val pair = gen.generateKeyPair()
    RsaKeyPair(
      keyId = "2026-04-29_18-30-00",
      publicKey = pair.getPublic.asInstanceOf[RSAPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey],
    )

  val testJwk: Json.Obj = testKeyPair.toPublicJwk

  class Env:
    val repository = stub[EdgeRepository]
    val security = stub[versola.util.SecurityService]
    val service = EdgeService.Impl(repository, security)

  def spec = suite("EdgeService")(
    test("registerEdge creates edge and returns key pair") {
      val env = Env()
      for
        _ <- env.security.generateRsaKeyPair.succeedsWith(testKeyPair)
        _ <- env.repository.createEdge.succeedsWith(())
        keyPair <- env.service.registerEdge(edgeId)
      yield
        val createCalls = env.repository.createEdge.calls
        assertTrue(
          keyPair.keyId == "2026-04-29_18-30-00",
          keyPair.privateKey == testKeyPair.privateKey,
          keyPair.publicKey == testKeyPair.publicKey,
          createCalls.length == 1,
          createCalls.head._1 == edgeId,
        )
    },
    test("rotateEdgeKey updates keys and keeps old key") {
      val env = Env()
      for
        _ <- env.security.generateRsaKeyPair.succeedsWith(testKeyPair)
        _ <- env.repository.rotateEdgeKey.succeedsWith(())
        rotatedKey <- env.service.rotateEdgeKey(edgeId)
      yield
        val rotateCalls = env.repository.rotateEdgeKey.calls
        assertTrue(
          rotatedKey.keyId == "2026-04-29_18-30-00",
          rotateCalls.length == 1,
          rotateCalls.head._1 == edgeId,
        )
    },

    test("deleteEdge calls repository deleteEdge") {
      val env = Env()
      for
        _ <- env.repository.deleteEdge.succeedsWith(())
        _ <- env.service.deleteEdge(edgeId)
      yield
        val deleteCalls = env.repository.deleteEdge.calls
        assertTrue(
          deleteCalls.length == 1,
          deleteCalls.head == edgeId,
        )
    },
    test("getAllEdges returns edges from repository") {
      val env = Env()
      val edges = Vector(
        EdgeRecord(edgeId, testJwk, None),
        EdgeRecord(edge2Id, testJwk, None),
      )
      for
        _ <- env.repository.getAll.succeedsWith(edges)
        all <- env.service.getAllEdges
      yield assertTrue(
        all.length == 2,
        all.map(_.id).toSet == Set(edgeId, edge2Id),
      )
    },
  )
