import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.math.BigInteger
import java.security.spec.{ECGenParameterSpec, ECParameterSpec, ECPoint, ECPublicKeySpec, PKCS8EncodedKeySpec}
import java.security.{AlgorithmParameters, KeyFactory, SecureRandom, Signature}
import java.util.Base64

/** The JWT signing key gen-env.scala generates: an EC P-256 key published under ES256, and the
  * JWKS central is bootstrapped with. Auth signs with the private half (`JWT_PRIVATE_KEY`) and
  * resolves it against this JWKS, so the two must be one pair.
  */
object JwtSigningKeySpec extends ZIOSpecDefault:

  private val rng = SecureRandom()

  private def jsonObj(raw: String): Json.Obj =
    raw.fromJson[Json.Obj].fold(e => throw AssertionError(e), identity)

  private def str(obj: Json.Obj, field: String): Option[String] =
    obj.get(field).collect { case Json.Str(value) => value }

  private def publicKeyOf(jwk: Json.Obj) =
    val params = AlgorithmParameters.getInstance("EC")
    params.init(ECGenParameterSpec("secp256r1"))
    val spec = params.getParameterSpec(classOf[ECParameterSpec])
    def int(field: String) = BigInteger(1, Base64.getUrlDecoder.decode(str(jwk, field).get))
    KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(int("x"), int("y")), spec))

  def spec = suite("JWT signing key")(
    test("is an EC P-256 key with a jwt-es256-<date> kid, published under ES256 for signing") {
      val key = genJwtSigningKey(rng, "2026-10-21")
      val jwk = jsonObj(key.jwk)
      assertTrue(
        key.kid == "jwt-es256-2026-10-21",
        str(jwk, "kty").contains("EC"),
        str(jwk, "crv").contains("P-256"),
        str(jwk, "alg").contains("ES256"),
        str(jwk, "use").contains("sig"),
        str(jwk, "kid").contains("jwt-es256-2026-10-21"),
        // A public JWK: no private scalar.
        jwk.get("d").isEmpty,
      )
    },
    test("JWT_PRIVATE_KEY is PKCS#8 base64 of an EC key") {
      val key = genJwtSigningKey(rng, "2026-10-21")
      val parsed = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(key.privateB64)))
      assertTrue(parsed.getAlgorithm == "EC")
    },
    test("the published JWK verifies what the private key signs") {
      val key = genJwtSigningKey(rng, "2026-10-21")
      val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(key.privateB64)))
      val message = "payload".getBytes("UTF-8")
      val signer = Signature.getInstance("SHA256withECDSA")
      signer.initSign(priv)
      signer.update(message)
      val signature = signer.sign()
      val verifier = Signature.getInstance("SHA256withECDSA")
      verifier.initVerify(publicKeyOf(jsonObj(key.jwk)))
      verifier.update(message)
      assertTrue(verifier.verify(signature))
    },
    test("JWKS_JSON publishes only that key: no RSA entry whose private half nobody holds") {
      val key = genJwtSigningKey(rng, "2026-10-21")
      val keys = jsonObj(jwtJwks(key)).get("keys").collect { case Json.Arr(elements) => elements.toList }.get
      assertTrue(
        keys.size == 1,
        keys.head == jsonObj(key.jwk),
      )
    },
    test("the edge key stays RSA") {
      val edge = genRsaKey(rng, "edge-2026-10-21")
      val jwk = jsonObj(edge.jwk)
      assertTrue(str(jwk, "kty").contains("RSA"), str(jwk, "alg").contains("RS256"))
    },
    test("the seeded server metadata advertises ES256 for ID tokens, the algorithm they are signed with") {
      val metadata = jsonObj(defaultServerMetadata("https://auth.example"))
      assertTrue(metadata.get("id_token_signing_alg_values_supported").contains(Json.Arr(Json.Str("ES256"))))
    },
  )
