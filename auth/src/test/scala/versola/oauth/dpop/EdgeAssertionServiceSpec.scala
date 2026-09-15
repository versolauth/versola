package versola.oauth.dpop

import com.nimbusds.jose.jwk.RSAKey
import versola.oauth.client.EdgeRegistrySyncClient.EdgeRegistration
import versola.oauth.client.model.TenantId
import versola.util.{EdgeAssertion, JWT, ReloadingCache, UnitSpecBase}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object EdgeAssertionServiceSpec extends UnitSpecBase:

  private val generator = KeyPairGenerator.getInstance("RSA")
  generator.initialize(2048)

  private val edgeKeyPair = generator.generateKeyPair()
  private val edgePrivateKey = edgeKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val edgePublicKey = edgeKeyPair.getPublic.asInstanceOf[RSAPublicKey]

  private val unregisteredKeyPair = generator.generateKeyPair()
  private val unregisteredPrivateKey = unregisteredKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]

  private val edgeId = "edge-1"
  private val keyId = "kid-1"
  private val accessToken = "access-token-1"
  private val tenantId = TenantId("tenant-1")
  private val otherTenantId = TenantId("tenant-2")

  private def keySet(publicKey: RSAPublicKey): JWT.PublicKeys =
    val jwk = RSAKey.Builder(publicKey).keyID(keyId).build().toJSONString
    JWT.PublicKeys.fromJson(
      Json.Obj("keys" -> Json.Arr(jwk.fromJson[Json.Obj].getOrElse(Json.Obj()))),
    )

  /** Stands in for what `EdgeRegistrySyncClient` keeps synced from central. `fresh` answers
    * every replay check, so a test only has to say when it expects a repeat to be seen.
    */
  private def service(
      registry: Map[String, EdgeRegistration],
      fresh: Boolean = true,
  ): UIO[EdgeAssertionService] =
    for
      ref <- Ref.make(registry)
      proofRepository = stub[DpopProofRepository]
      _ <- proofRepository.recordIfAbsent.succeedsWith(fresh)
    yield EdgeAssertionService.Impl(ReloadingCache(ref), proofRepository)

  private val registered = Map(edgeId -> EdgeRegistration(keySet(edgePublicKey), Set(tenantId)))

  def spec = suite("EdgeAssertionService")(
    test("names the edge that signed for this token") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result == Some(edgeId))
    },
    test("rejects an assertion naming an edge central has not registered") {
      for
        assertion <- EdgeAssertion.issue("edge-nobody-knows", keyId, edgePrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result.isEmpty)
    },
    // The check that makes one edge's identity useless for another's: the id is read off an
    // unverified header, so it is the key lookup that has to settle who signed.
    test("rejects an assertion that names a registered edge but is signed with another key") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, unregisteredPrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result.isEmpty)
    },
    test("rejects an assertion minted for a different access token") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, "another-access-token")
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result.isEmpty)
    },
    // The high-severity gap this suite exists to close: a signature that checks out under a
    // registered edge's key still says nothing about which tenants that edge may vouch for.
    // Without this check, any edge's key binds any tenant's tokens -- central's own tenant
    // assignment is what has to draw the line an assertion's identity alone cannot.
    test("rejects an assertion from an edge that is registered but not assigned to this tenant") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken, otherTenantId)
      yield assertTrue(result.isEmpty)
    },
    // A tenant with no edge assigned (`edgeId = None` centrally) never reaches the registry at
    // all, so no edge's assertion should ever be honoured for its tokens.
    test("rejects every assertion for a tenant no edge is assigned to serve") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(Map(edgeId -> EdgeRegistration(keySet(edgePublicKey), Set.empty)))
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result.isEmpty)
    },
    // The medium-severity gap: an assertion is a bearer credential for the downgrade refusal
    // it exists to answer, good until its own 2-minute expiry, so it has to be spent once --
    // the same one-time semantics `DpopService` enforces on the client's own proof.
    test("rejects a second presentation of the exact same assertion") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(registered, fresh = false)
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result.isEmpty)
    },
    test("rejects anything that is not an assertion at all, without failing") {
      for
        subject <- service(registered)
        garbage <- subject.verify("not-a-jwt", accessToken, tenantId)
        empty <- subject.verify("", accessToken, tenantId)
      yield assertTrue(garbage.isEmpty, empty.isEmpty)
    },
    test("rejects every assertion while the registry is still empty") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(Map.empty)
        result <- subject.verify(assertion, accessToken, tenantId)
      yield assertTrue(result.isEmpty)
    },
  ) @@ TestAspect.silentLogging
