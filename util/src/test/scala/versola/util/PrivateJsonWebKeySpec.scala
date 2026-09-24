package versola.util

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{Curve, ECKey, JWKSet, KeyUse, RSAKey}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import scala.jdk.CollectionConverters.*

/** The signing half of `private_key_jwt` and JAR: what an edge is provisioned with, and what
  * it refuses to be provisioned with.
  *
  * The round trips below verify against [[ClientAssertion.verify]] and
  * [[RequestObject.verify]] rather than against a hand-rolled check, because what makes a
  * signature useful is that *those* accept it -- a test that only re-read what it wrote would
  * pass for a key the server rejects.
  */
object PrivateJsonWebKeySpec extends ZIOSpecDefault:

  private val ClientId = "web-app"
  private val Issuer = "https://idp.example"

  private val rsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val ecKeyPair =
    val generator = KeyPairGenerator.getInstance("EC").nn
    generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair().nn

  private def rsaJwk(
      kid: Option[String] = Some("rsa-1"),
      alg: Option[JWSAlgorithm] = Some(JWSAlgorithm.PS256),
      keyUse: Option[KeyUse] = None,
      withPrivate: Boolean = true,
  ): Json.Obj =
    val builder = RSAKey.Builder(rsaKeyPair.getPublic.nn.asInstanceOf[RSAPublicKey])
    if withPrivate then builder.privateKey(rsaKeyPair.getPrivate.nn.asInstanceOf[RSAPrivateKey])
    kid.foreach(builder.keyID)
    alg.foreach(builder.algorithm)
    keyUse.foreach(builder.keyUse)
    document(builder.build().nn.toJSONString.nn)

  private val ecJwk: Json.Obj =
    document(
      ECKey.Builder(Curve.P_256, ecKeyPair.getPublic.nn.asInstanceOf[ECPublicKey])
        .privateKey(ecKeyPair.getPrivate.nn.asInstanceOf[ECPrivateKey])
        .keyID("ec-1")
        .algorithm(JWSAlgorithm.ES256)
        .build().nn.toJSONString.nn,
    )

  private def document(json: String): Json.Obj =
    json.fromJson[Json.Obj].toOption.get

  private def keySet(keys: Json.Obj*): JsonWebKeySet =
    JsonWebKeySet(
      document(
        JWKSet(
          keys.map(key => com.nimbusds.jose.jwk.JWK.parse(key.toJson).nn.toPublicJWK.nn).toList.asJava,
        ).nn.toString(true).nn,
      ),
    )

  def spec = suite("PrivateJsonWebKey")(validateSuite, signingSuite, publishedInSuite, roundTripSuite)

  private val validateSuite = suite("validate")(
    test("accepts an RSA key that declares a usable alg and a key id") {
      assertTrue(PrivateJsonWebKey.validate(rsaJwk()).isRight)
    },
    test("accepts a P-256 EC key") {
      assertTrue(PrivateJsonWebKey.validate(ecJwk).isRight)
    },
    test("refuses a public key - a signer that cannot sign is not a signer") {
      assertTrue(
        PrivateJsonWebKey.validate(rsaJwk(withPrivate = false))
          .left.exists(_.contains("private")),
      )
    },
    test("refuses a key with no key id, which a signature header could not name") {
      assertTrue(
        PrivateJsonWebKey.validate(rsaJwk(kid = None))
          .left.exists(_.contains("key id")),
      )
    },
    test("refuses a key that declares no alg rather than guessing one from its type") {
      assertTrue(
        PrivateJsonWebKey.validate(rsaJwk(alg = None))
          .left.exists(_.contains("alg")),
      )
    },
    test("refuses an alg this server has no signer for") {
      assertTrue(
        PrivateJsonWebKey.validate(rsaJwk(alg = Some(JWSAlgorithm.RS512)))
          .left.exists(_.contains("RS512")),
      )
    },
    test("refuses a key registered for encryption") {
      assertTrue(
        PrivateJsonWebKey.validate(rsaJwk(keyUse = Some(KeyUse.ENCRYPTION)))
          .left.exists(_.contains("encryption")),
      )
    },
    test("refuses a document that is not a JWK at all") {
      assertTrue(
        PrivateJsonWebKey.validate(Json.Obj("kty" -> Json.Str("nonsense")))
          .left.exists(_.contains("must be a JWK")),
      )
    },
  )

  private val signingSuite = suite("signing")(
    test("carries the key id and algorithm a verifier selects the public key by") {
      val signing = PrivateJsonWebKey(rsaJwk()).signing
      assertTrue(
        signing.map(_.keyId) == Right("rsa-1"),
        signing.map(_.algorithm) == Right(ClientAssertion.Algorithm.PS256),
      )
    },
    test("reports why a key cannot sign instead of yielding an unusable signer") {
      assertTrue(PrivateJsonWebKey(rsaJwk(withPrivate = false)).signing.isLeft)
    },
  )

  private val publishedInSuite = suite("publishedIn")(
    test("accepts a key whose public half the set publishes under the same key id") {
      assertTrue(
        PrivateJsonWebKey.publishedIn(PrivateJsonWebKey(rsaJwk()), keySet(rsaJwk())).isRight,
      )
    },
    test("accepts a published half that differs only in members not part of the key") {
      // The public half a client registers need not repeat the `alg` the private half
      // declares, so comparing documents would refuse a perfectly matched pair.
      val bare = document(
        RSAKey.Builder(rsaKeyPair.getPublic.nn.asInstanceOf[RSAPublicKey])
          .keyID("rsa-1")
          .build().nn.toJSONString.nn,
      )
      assertTrue(
        PrivateJsonWebKey.publishedIn(PrivateJsonWebKey(rsaJwk()), keySet(bare)).isRight,
      )
    },
    test("refuses a key the set does not publish at all") {
      assertTrue(
        PrivateJsonWebKey.publishedIn(PrivateJsonWebKey(rsaJwk()), keySet(ecJwk))
          .left.exists(_.contains("does not publish")),
      )
    },
    test("refuses a set that publishes a different key under the same key id") {
      // The failure most worth catching: the registration looks consistent, and every
      // signature made with it is refused for a reason neither end names.
      val impostor = document(
        ECKey.Builder(Curve.P_256, ecKeyPair.getPublic.nn.asInstanceOf[ECPublicKey])
          .keyID("rsa-1")
          .algorithm(JWSAlgorithm.ES256)
          .build().nn.toJSONString.nn,
      )
      assertTrue(
        PrivateJsonWebKey.publishedIn(PrivateJsonWebKey(rsaJwk()), keySet(impostor))
          .left.exists(_.contains("does not match")),
      )
    },
  )

  private val roundTripSuite = suite("round trips")(
    test("an assertion issued with the key verifies against the published public half") {
      for
        signing <- ZIO.fromEither(PrivateJsonWebKey(rsaJwk()).signing)
        token <- ClientAssertion.issue(
          clientId = ClientId,
          audience = Issuer,
          algorithm = signing.algorithm,
          keyId = signing.keyId,
          privateKey = signing.privateKey,
        )
        now <- Clock.instant
        verified <- ClientAssertion.verify(
          token = token,
          keys = keySet(rsaJwk()).publicKeys.toOption.get,
          allowedAlgorithms = ClientAssertion.Algorithm.Default,
          clientId = ClientId,
          acceptedAudiences = Set(Issuer),
          now = now,
          maxLifetime = 5.minutes,
        )
      yield assertTrue(verified.jti.nonEmpty)
    },
    test("an EC key round trips the same way, so the enum is not RSA-only in practice") {
      for
        signing <- ZIO.fromEither(PrivateJsonWebKey(ecJwk).signing)
        token <- ClientAssertion.issue(ClientId, Issuer, signing.algorithm, signing.keyId, signing.privateKey)
        now <- Clock.instant
        verified <- ClientAssertion.verify(
          token,
          keySet(ecJwk).publicKeys.toOption.get,
          ClientAssertion.Algorithm.Default,
          ClientId,
          Set(Issuer),
          now,
          5.minutes,
        ).either
      yield assertTrue(verified.isRight)
    },
    test("a signed request object verifies and yields back the parameters it was given") {
      for
        signing <- ZIO.fromEither(PrivateJsonWebKey(rsaJwk()).signing)
        token <- RequestObject.sign(
          parameters = Map(
            "client_id" -> Chunk(ClientId),
            "response_type" -> Chunk("code"),
            "scope" -> Chunk("openid email"),
            "resource" -> Chunk("https://a.example", "https://b.example"),
          ),
          clientId = ClientId,
          audience = Issuer,
          algorithm = signing.algorithm,
          keyId = signing.keyId,
          privateKey = signing.privateKey,
        )
        now <- Clock.instant
        claims <- RequestObject.verify(
          token = token,
          keys = keySet(rsaJwk()).publicKeys.toOption.get,
          allowedAlgorithms = ClientAssertion.Algorithm.Default,
          clientId = ClientId,
          acceptedAudiences = Set(Issuer),
          now = now,
          maxLifetime = 10.minutes,
        )
        parameters = RequestObject.parameters(claims)
      yield assertTrue(
        parameters("response_type") == Chunk("code"),
        parameters("scope") == Chunk("openid email"),
        // A repeated parameter survives as one, rather than collapsing to its first value.
        parameters("resource") == Chunk("https://a.example", "https://b.example"),
      )
    },
    test("a signed object carries no `sub`, which verification refuses as an assertion") {
      // RFC 9101 §10.8: an object carrying `sub` = the client is indistinguishable from a
      // client assertion over the same key set, so `sign` must never produce one.
      for
        signing <- ZIO.fromEither(PrivateJsonWebKey(rsaJwk()).signing)
        token <- RequestObject.sign(
          Map("client_id" -> Chunk(ClientId)),
          ClientId,
          Issuer,
          signing.algorithm,
          signing.keyId,
          signing.privateKey,
        )
        now <- Clock.instant
        claims <- RequestObject.verify(
          token,
          keySet(rsaJwk()).publicKeys.toOption.get,
          ClientAssertion.Algorithm.Default,
          ClientId,
          Set(Issuer),
          now,
          10.minutes,
        )
      yield assertTrue(claims.get("sub").isEmpty)
    },
    test("a caller cannot smuggle its own `iss` in as a request parameter") {
      for
        signing <- ZIO.fromEither(PrivateJsonWebKey(rsaJwk()).signing)
        token <- RequestObject.sign(
          Map("client_id" -> Chunk(ClientId), "iss" -> Chunk("https://attacker.example")),
          ClientId,
          Issuer,
          signing.algorithm,
          signing.keyId,
          signing.privateKey,
        )
        now <- Clock.instant
        claims <- RequestObject.verify(
          token,
          keySet(rsaJwk()).publicKeys.toOption.get,
          ClientAssertion.Algorithm.Default,
          ClientId,
          Set(Issuer),
          now,
          10.minutes,
        )
      yield assertTrue(claims.get("iss") == Some(Json.Str(ClientId)))
    },
  )
