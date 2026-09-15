package versola.oauth.dpop

import com.nimbusds.jose.jwk.RSAKey
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
  private val accessToken = "bound-access-token"

  private def keySet(publicKey: RSAPublicKey): JWT.PublicKeys =
    val jwk = RSAKey.Builder(publicKey).keyID(keyId).build().toJSONString
    JWT.PublicKeys.fromJson(
      Json.Obj("keys" -> Json.Arr(jwk.fromJson[Json.Obj].getOrElse(Json.Obj()))),
    )

  /** Stands in for what `EdgeRegistrySyncClient` keeps synced from central. */
  private def service(registry: Map[String, JWT.PublicKeys]): UIO[EdgeAssertionService] =
    Ref.make(registry).map(ref => EdgeAssertionService.Impl(ReloadingCache(ref)))

  private val registered = Map(edgeId -> keySet(edgePublicKey))

  def spec = suite("EdgeAssertionService")(
    test("names the edge that signed for this token") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken)
      yield assertTrue(result == Some(edgeId))
    },
    test("rejects an assertion naming an edge central has not registered") {
      for
        assertion <- EdgeAssertion.issue("edge-nobody-knows", keyId, edgePrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken)
      yield assertTrue(result.isEmpty)
    },
    // The check that makes one edge's identity useless for another's: the id is read off an
    // unverified header, so it is the key lookup that has to settle who signed.
    test("rejects an assertion that names a registered edge but is signed with another key") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, unregisteredPrivateKey, accessToken)
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken)
      yield assertTrue(result.isEmpty)
    },
    test("rejects an assertion minted for a different access token") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, "another-access-token")
        subject <- service(registered)
        result <- subject.verify(assertion, accessToken)
      yield assertTrue(result.isEmpty)
    },
    test("rejects anything that is not an assertion at all, without failing") {
      for
        subject <- service(registered)
        garbage <- subject.verify("not-a-jwt", accessToken)
        empty <- subject.verify("", accessToken)
      yield assertTrue(garbage.isEmpty, empty.isEmpty)
    },
    test("rejects every assertion while the registry is still empty") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        subject <- service(Map.empty)
        result <- subject.verify(assertion, accessToken)
      yield assertTrue(result.isEmpty)
    },
  ) @@ TestAspect.silentLogging
