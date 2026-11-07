package versola.central.configuration.jwks

import zio.json.ast.Json
import zio.test.*

object JwkValidationSpec extends ZIOSpecDefault:
  import TestJwks.*

  private def refused(jwk: Json.Obj): Either[String, Unit] = JwkValidation.validate(jwk)

  def spec = suite("JwkValidation")(
    suite("accepts a public key")(
      test("an RSA key with alg and use") {
        assertTrue(JwkValidation.validate(rsaPublic) == Right(()))
      },
      test("an EC key with alg and use") {
        assertTrue(JwkValidation.validate(ecPublic) == Right(()))
      },
      test("a key published without alg, as a verify-only key may be") {
        assertTrue(JwkValidation.validate(without(rsaPublic, "alg")) == Right(()))
      },
    ),
    suite("refuses private material")(
      test("an RSA key that carries its private members") {
        val result = refused(rsaPrivate)
        assertTrue(result.left.exists(_.contains("private members")), result.left.exists(_.contains("d, dp, dq, p, q, qi")))
      },
      test("an EC key that carries d") {
        assertTrue(refused(ecPrivate).left.exists(_.contains("(d)")))
      },
      test("each private member on its own, including a symmetric key's k and oth") {
        val results = JwkValidation.PrivateMembers.toList.map(member => member -> refused(withField(rsaPublic, member, Json.Str("x"))))
        assertTrue(results.forall((_, result) => result.left.exists(_.contains("private members"))))
      },
      test("the reason names the member and never its value") {
        val secret = "super-secret-value-123"
        val result = refused(withField(rsaPublic, "d", Json.Str(secret)))
        assertTrue(result.left.exists(!_.contains(secret)))
      },
    ),
    suite("refuses a key that is not usable")(
      test("a key type other than RSA or EC") {
        assertTrue(refused(Json.Obj("kid" -> Json.Str("k"), "kty" -> Json.Str("oct"))).isLeft)
      },
      test("no kty") {
        assertTrue(refused(without(rsaPublic, "kty")).isLeft)
      },
      test("an RSA key without its modulus") {
        assertTrue(refused(without(rsaPublic, "n")).left.exists(_.contains("n")))
      },
      test("an EC key without a coordinate") {
        assertTrue(refused(without(ecPublic, "y")).left.exists(_.contains("y")))
      },
      test("an HS256 alg, a shared secret is not a JWKS key") {
        assertTrue(refused(withField(rsaPublic, "alg", Json.Str("HS256"))).isLeft)
      },
      test("an alg this service cannot verify with") {
        assertTrue(refused(withField(rsaPublic, "alg", Json.Str("RS512"))).isLeft)
      },
      test("an alg that does not fit the key type") {
        assertTrue(refused(withField(ecPublic, "alg", Json.Str("PS256"))).isLeft)
      },
      test("an EC key on a curve other than P-256, the only one supported") {
        val p384 = com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_384).keyID("ec-384").generate()
        val key = zio.json.DecoderOps(p384.toPublicJWK.nn.toJSONString.nn).fromJson[Json.Obj].toOption.get
        assertTrue(refused(key).left.exists(_.contains("P-256")))
      },
      test("a use other than sig and enc") {
        assertTrue(refused(withField(rsaPublic, "use", Json.Str("anything"))).isLeft)
      },
      test("a point that is not on the curve") {
        assertTrue(refused(withField(ecPublic, "x", Json.Str("AAAA"))).isLeft)
      },
    ),
    suite("publicProjection")(
      test("keeps the published members of a public key as they are") {
        assertTrue(JwkValidation.publicProjection(rsaPublic) == rsaPublic, JwkValidation.publicProjection(ecPublic) == ecPublic)
      },
      test("drops the private members of a stored row") {
        val projected = JwkValidation.publicProjection(rsaPrivate)
        assertTrue(projected.fields.map(_._1).forall(JwkValidation.PublishedMembers.contains), projected == rsaPublic)
      },
      test("drops a member it does not know, whatever it holds") {
        val projected = JwkValidation.publicProjection(withField(rsaPublic, "internal-note", Json.Str("x")))
        assertTrue(projected == rsaPublic)
      },
    ),
  )
