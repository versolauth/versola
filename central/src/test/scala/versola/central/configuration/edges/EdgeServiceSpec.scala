package versola.central.configuration.edges

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.tenants.TenantId
import versola.util.http.{AccessLogPolicy, InboundPolicy, OverloadPolicy}
import versola.util.{ReloadingCache, RsaKeyPair}
import zio.json.ast.Json
import zio.test.*
import zio.{Exit, Ref, UIO, Unsafe, ZIO}

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

  class Env(initial: Vector[EdgeRecord] = Vector.empty):
    val repository = stub[EdgeRepository]
    val security = stub[versola.util.SecurityService]
    val cache: ReloadingCache[Vector[EdgeRecord]] =
      ReloadingCache(Unsafe.unsafe(Ref.unsafe.make(initial)(using _)))
    val service = EdgeService.Impl(cache, repository, security)

  def spec = suite("EdgeService")(
    test("registerEdge creates edge and returns key pair") {
      val env = Env()
      for
        _ <- env.security.generateRsaKeyPair.succeedsWith(testKeyPair)
        _ <- env.repository.createEdge.succeedsWith(())
        keyPair <- env.service.registerEdge(edgeId, EdgeGroup.DefaultId)
      yield
        val createCalls = env.repository.createEdge.calls
        assertTrue(
          keyPair.keyId == "2026-04-29_18-30-00",
          keyPair.privateKey == testKeyPair.privateKey,
          keyPair.publicKey == testKeyPair.publicKey,
          createCalls.length == 1,
          createCalls.head._1 == edgeId,
          createCalls.head._4 == EdgeGroup.DefaultId,
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
    test("setRequireDpopNonce records the value against the named edge") {
      val env = Env()
      for
        _ <- env.repository.setRequireDpopNonce.succeedsWith(())
        _ <- env.service.setRequireDpopNonce(edgeId, false)
      yield assertTrue(env.repository.setRequireDpopNonce.calls == List((edgeId, false)))
    },
    test("setOverloadPolicy records a valid policy against the named edge") {
      val env = Env()
      val policy = OverloadPolicy(inbound = Some(InboundPolicy(mode = Some("enforce"), maxInFlight = Some(100))))
      for
        _ <- env.repository.setOverloadPolicy.succeedsWith(())
        _ <- env.service.setOverloadPolicy(edgeId, policy)
      yield assertTrue(env.repository.setOverloadPolicy.calls == List((edgeId, Some(policy))))
    },
    test("an empty policy is stored as none, returning the edge to its deployment's values") {
      val env = Env()
      for
        _ <- env.repository.setOverloadPolicy.succeedsWith(())
        _ <- env.service.setOverloadPolicy(edgeId, OverloadPolicy.empty)
      yield assertTrue(env.repository.setOverloadPolicy.calls == List((edgeId, None)))
    },
    test("a policy that does not validate is refused with the field named, and nothing is written") {
      val env = Env()
      for
        _ <- env.repository.setOverloadPolicy.succeedsWith(())
        result <- env.service.setOverloadPolicy(edgeId, OverloadPolicy(inbound = Some(InboundPolicy(maxInFlight = Some(0))))).exit
      yield assertTrue(
        result match
          case Exit.Failure(c) => c.failureOption.exists(e => e.isInstanceOf[InvalidOverloadPolicy] && e.getMessage.contains("inbound.maxInFlight"))
          case _ => false,
        env.repository.setOverloadPolicy.calls.isEmpty,
      )
    },
    test("setAccessLogPolicy records a valid policy against the named edge") {
      val env = Env()
      val policy = AccessLogPolicy(mode = Some("sampled"), successSampleRate = Some(0.1))
      for
        _ <- env.repository.setAccessLogPolicy.succeedsWith(())
        _ <- env.service.setAccessLogPolicy(edgeId, policy)
      yield assertTrue(env.repository.setAccessLogPolicy.calls == List((edgeId, Some(policy))))
    },
    test("an empty access-log policy is stored as none, returning the edge to its deployment's values") {
      val env = Env()
      for
        _ <- env.repository.setAccessLogPolicy.succeedsWith(())
        _ <- env.service.setAccessLogPolicy(edgeId, AccessLogPolicy.empty)
      yield assertTrue(env.repository.setAccessLogPolicy.calls == List((edgeId, None)))
    },
    test("an access-log policy that does not validate is refused with the field named, and nothing is written") {
      val env = Env()
      for
        _ <- env.repository.setAccessLogPolicy.succeedsWith(())
        result <- env.service.setAccessLogPolicy(edgeId, AccessLogPolicy(successSampleRate = Some(1.5))).exit
      yield assertTrue(
        result match
          case Exit.Failure(c) => c.failureOption.exists(e => e.isInstanceOf[InvalidAccessLogPolicy] && e.getMessage.contains("successSampleRate"))
          case _ => false,
        env.repository.setAccessLogPolicy.calls.isEmpty,
      )
    },
    test("getAllEdges returns edges from cache") {
      val edges = Vector(
        EdgeRecord(edge2Id, testJwk, None, requireDpopNonce = true),
        EdgeRecord(edgeId, testJwk, None, requireDpopNonce = true),
      )
      val env = Env(initial = edges)
      for all <- env.service.getAllEdges
      yield assertTrue(
        all.length == 2,
        all.map(_.id) == Vector(edgeId, edge2Id),
      )
    },
    test("find returns edge from cache") {
      val edges = Vector(EdgeRecord(edgeId, testJwk, None, requireDpopNonce = true))
      val env = Env(initial = edges)
      for
        found <- env.service.find(edgeId)
        notFound <- env.service.find(edge2Id)
      yield assertTrue(
        found.exists(_.id == edgeId),
        notFound.isEmpty,
      )
    },
    test("sync reloads cache from repository") {
      val edges = Vector(EdgeRecord(edgeId, testJwk, None, requireDpopNonce = true))
      val env = Env()
      for
        _ <- env.repository.getAll.succeedsWith(edges)
        _ <- env.service.sync()
        all <- env.service.getAllEdges
      yield assertTrue(
        all == edges,
      )
    },
  )
