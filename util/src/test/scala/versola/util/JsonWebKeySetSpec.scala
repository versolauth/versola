package versola.util

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{Curve, ECKey, KeyUse, RSAKey}
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}

object JsonWebKeySetSpec extends ZIOSpecDefault:

  private val ecKeyPairGenerator = KeyPairGenerator.getInstance("EC")
  ecKeyPairGenerator.initialize(Curve.P_256.toECParameterSpec)

  private def ecKey(kid: String): ECKey =
    val pair = ecKeyPairGenerator.generateKeyPair()
    ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).keyID(kid).build()

  private def ecKeyPairWithPrivate(kid: String): ECKey =
    val pair = ecKeyPairGenerator.generateKeyPair()
    ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey])
      .privateKey(pair.getPrivate.asInstanceOf[ECPrivateKey])
      .keyID(kid)
      .build()

  private val p384KeyPairGenerator = KeyPairGenerator.getInstance("EC")
  p384KeyPairGenerator.initialize(Curve.P_384.toECParameterSpec)

  private val rsaKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
  rsaKeyPairGenerator.initialize(2048)

  private def rsaKey(kid: String): RSAKey =
    val pair = rsaKeyPairGenerator.generateKeyPair()
    RSAKey.Builder(pair.getPublic.asInstanceOf[RSAPublicKey]).keyID(kid).build()

  private def document(keys: String*): Json.Obj =
    Json.decoder.decodeJson(s"""{"keys":[${keys.mkString(",")}]}""")
      .toOption.get.asInstanceOf[Json.Obj]

  def spec = suite("JsonWebKeySet")(
    suite("validate")(
      test("accepts a set of public RSA and EC keys") {
        val json = document(ecKey("ec-1").toJSONString, rsaKey("rsa-1").toJSONString)
        assertTrue(JsonWebKeySet.validate(json).isRight)
      },
      test("accepts a single key with no kid, which has nothing to disambiguate") {
        val pair = ecKeyPairGenerator.generateKeyPair()
        val key = ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).build()
        assertTrue(JsonWebKeySet.validate(document(key.toJSONString)).isRight)
      },
      test("rejects a document that is not a JWK Set") {
        val json = Json.decoder.decodeJson("""{"not":"a key set"}""").toOption.get.asInstanceOf[Json.Obj]
        assertTrue(JsonWebKeySet.validate(json).isLeft)
      },
      test("rejects an empty set, which could never authenticate anything") {
        assertTrue(JsonWebKeySet.validate(document()) == Left("must contain at least one key"))
      },
      test("rejects private key material rather than storing it unused") {
        val json = document(ecKeyPairWithPrivate("ec-1").toJSONString)
        assertTrue(JsonWebKeySet.validate(json) == Left("must contain public keys only"))
      },
      test("rejects a symmetric key, which would be client_secret_jwt") {
        val json = document("""{"kty":"oct","kid":"oct-1","k":"c2VjcmV0LXZhbHVlLWhlcmUtMzJieXRlcy1sb25n"}""")
        assertTrue(JsonWebKeySet.validate(json) == Left("must contain public keys only"))
      },
      test("rejects a key registered for encryption") {
        val pair = ecKeyPairGenerator.generateKeyPair()
        val key = ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey])
          .keyID("ec-1")
          .keyUse(KeyUse.ENCRYPTION)
          .build()
        assertTrue(
          JsonWebKeySet.validate(document(key.toJSONString)) ==
            Left("must not contain keys marked for encryption"),
        )
      },
      test("rejects an EC key on a curve no supported algorithm names") {
        val pair = p384KeyPairGenerator.generateKeyPair()
        val key = ECKey.Builder(Curve.P_384, pair.getPublic.asInstanceOf[ECPublicKey]).keyID("ec-1").build()
        assertTrue(JsonWebKeySet.validate(document(key.toJSONString)).isLeft)
      },
      test("rejects a key whose own alg its type cannot perform") {
        val pair = rsaKeyPairGenerator.generateKeyPair()
        val key = RSAKey.Builder(pair.getPublic.asInstanceOf[RSAPublicKey])
          .keyID("rsa-1")
          .algorithm(JWSAlgorithm.ES256)
          .build()
        assertTrue(JsonWebKeySet.validate(document(key.toJSONString)).isLeft)
      },
      test("accepts a key pinned to an algorithm its type can perform") {
        val pair = rsaKeyPairGenerator.generateKeyPair()
        val key = RSAKey.Builder(pair.getPublic.asInstanceOf[RSAPublicKey])
          .keyID("rsa-1")
          .algorithm(JWSAlgorithm.PS256)
          .build()
        assertTrue(JsonWebKeySet.validate(document(key.toJSONString)).isRight)
      },
      test("rejects a repeated key id, which cannot be indexed by kid") {
        val json = document(ecKey("same").toJSONString, rsaKey("same").toJSONString)
        assertTrue(JsonWebKeySet.validate(json) == Left("must not repeat a key id"))
      },
      test("rejects more keys than one request should ever verify against") {
        val keys = (1 to JsonWebKeySet.MaxKeys + 1).map(i => ecKey(s"ec-$i").toJSONString)
        assertTrue(
          JsonWebKeySet.validate(document(keys*)) ==
            Left(s"must not contain more than ${JsonWebKeySet.MaxKeys} keys"),
        )
      },
    ),
    suite("publicKeys")(
      test("parses a validated set into verification keys") {
        val json = document(ecKey("ec-1").toJSONString)
        val parsed = JsonWebKeySet.validate(json).flatMap(_.publicKeys)
        assertTrue(parsed.map(_.keys.getKeys.size) == Right(1))
      },
      test("keeps members it does not model, rather than dropping them on the way through") {
        val key = Json.decoder.decodeJson(ecKey("ec-1").toJSONString).toOption.get
          .asInstanceOf[Json.Obj]
          .add("x-custom", Json.Str("kept"))
        val json = Json.Obj("keys" -> Json.Arr(key))
        assertTrue(
          JsonWebKeySet.validate(json).map(_.document) == Right(json),
        )
      },
    ),
  )
