package versola.central.configuration.edges

import com.nimbusds.jose.jwk.gen.{ECKeyGenerator, RSAKeyGenerator}
import com.nimbusds.jose.jwk.{Curve, JWK}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** What central takes for the signing key of an edge that enrols or renews: a public key it can verify with, and nothing the
  * edge keeps secret. */
object EdgeSigningKeySpec extends ZIOSpecDefault:

  private def json(key: JWK): Json.Obj = key.toJSONString.fromJson[Json.Obj].toOption.get

  private def renamed(key: Json.Obj, kid: String): Json.Obj =
    Json.Obj(key.fields.map {
      case ("kid", _) => "kid" -> Json.Str(kid)
      case other => other
    }*)

  private val rsa = RSAKeyGenerator(2048).keyID("rsa-key").generate()
  private val ec = ECKeyGenerator(Curve.P_256).keyID("ec-key").generate()

  def spec = suite("EdgeSigningKey")(
    test("a public RSA key of 2048 bits is accepted as it is, and a P-256 key is not: the secrets sent to the edge are RSA-encrypted to it") {
      assertTrue(
        EdgeSigningKey.validate(json(rsa.toPublicJWK)) == Right(json(rsa.toPublicJWK)),
        EdgeSigningKey.validate(json(ec.toPublicJWK)).left.exists(_.contains("RSA")),
      )
    },
    test("a key with a private member is refused, whatever else is right about it") {
      assertTrue(
        EdgeSigningKey.validate(json(rsa)).left.exists(_.contains("public key only")),
        EdgeSigningKey.validate(json(ec)).left.exists(_.contains("public key only")),
      )
    },
    test("a key without a kid is refused: the token's kid header is how the key is found") {
      val unnamed = Json.Obj(json(rsa.toPublicJWK).fields.filterNot(_._1 == "kid")*)
      assertTrue(EdgeSigningKey.validate(unnamed).left.exists(_.contains("kid")))
    },
    test("a kid that is far too long is refused") {
      assertTrue(EdgeSigningKey.validate(renamed(json(rsa.toPublicJWK), "k" * 200)).isLeft)
    },
    test("an RSA key under 2048 bits, an EC key on another curve and a symmetric key are refused") {
      val small = RSAKeyGenerator(1024, true).keyID("small").generate()
      val p384 = ECKeyGenerator(Curve.P_384).keyID("p384").generate()
      assertTrue(
        EdgeSigningKey.validate(json(small.toPublicJWK)).left.exists(_.contains("1024")),
        EdgeSigningKey.validate(json(p384.toPublicJWK)).left.exists(_.contains("RSA")),
        EdgeSigningKey.validate(Json.Obj("kty" -> Json.Str("oct"), "kid" -> Json.Str("k"), "k" -> Json.Str("AAAA"))).isLeft,
      )
    },
    test("what is not a JWK is refused") {
      assertTrue(
        EdgeSigningKey.validate(Json.Obj()).isLeft,
        EdgeSigningKey.validate(Json.Obj("kty" -> Json.Str("RSA"))).isLeft,
      )
    },
    test("two JWKs of one key have one thumbprint, whatever their kid; two keys do not") {
      val publicRsa = json(rsa.toPublicJWK)
      assertTrue(
        EdgeSigningKey.thumbprint(publicRsa) == EdgeSigningKey.thumbprint(renamed(publicRsa, "another-name")),
        EdgeSigningKey.thumbprint(publicRsa) != EdgeSigningKey.thumbprint(json(ec.toPublicJWK)),
        EdgeSigningKey.thumbprint(publicRsa).isDefined,
      )
    },
  )
