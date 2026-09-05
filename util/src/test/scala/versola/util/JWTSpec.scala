package versola.util

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import javax.crypto.KeyGenerator

object JWTSpec extends ZIOSpecDefault:

  // Test RSA key pair
  private val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
  keyPairGenerator.initialize(2048)
  private val keyPair = keyPairGenerator.generateKeyPair()
  private val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val publicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]

  private val publicKeys = JWT.PublicKeys(
    com.nimbusds.jose.jwk.JWKSet(
      new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey)
        .keyID("test-key-1")
        .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
        .build(),
    ),
  )

  // Test symmetric key
  private val keyGenerator = KeyGenerator.getInstance("HmacSHA256")
  keyGenerator.init(256)
  private val symmetricKey = keyGenerator.generateKey()

  // Test EC key pair, registered under its own kid so `verifySignature`'s ECKey branch
  // (as opposed to the RSAKey branch every other asymmetric test exercises) gets hit.
  private val ecKeyPairGenerator = KeyPairGenerator.getInstance("EC")
  ecKeyPairGenerator.initialize(com.nimbusds.jose.jwk.Curve.P_256.toECParameterSpec)
  private val ecKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val ecPublicKeys = JWT.PublicKeys(
    com.nimbusds.jose.jwk.JWKSet(
      new com.nimbusds.jose.jwk.ECKey.Builder(
        com.nimbusds.jose.jwk.Curve.P_256,
        ecKeyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey],
      ).keyID("ec-key-1").build(),
    ),
  )

  // Test HMAC key registered as an octet-sequence JWK (rather than passed directly), so
  // `verifySignature`'s OctetSequenceKey branch gets hit via the [[JWT.PublicKeys]] path.
  private val octetPublicKeys = JWT.PublicKeys(
    com.nimbusds.jose.jwk.JWKSet(
      new com.nimbusds.jose.jwk.OctetSequenceKey.Builder(symmetricKey.getEncoded)
        .keyID("hmac-key-1").build(),
    ),
  )

  // Test claims
  case class TestClaims(sub: String, name: String, admin: Boolean) derives JsonCodec

  private def signRawWithHeader(
      keyId: String,
      algorithm: com.nimbusds.jose.JWSAlgorithm,
      signer: com.nimbusds.jose.JWSSigner,
  )(build: com.nimbusds.jwt.JWTClaimsSet.Builder => com.nimbusds.jwt.JWTClaimsSet.Builder) =
    ZIO.attempt {
      val claims = build(
        com.nimbusds.jwt.JWTClaimsSet.Builder()
          .jwtID("jti-1")
          .expirationTime(java.util.Date.from(java.time.Instant.parse("2100-01-01T00:00:00Z"))),
      ).build()
      val header = com.nimbusds.jose.JWSHeader.Builder(algorithm)
        .keyID(keyId)
        .`type`(com.nimbusds.jose.JOSEObjectType.JWT)
        .build()
      val jwt = com.nimbusds.jwt.SignedJWT(header, claims)
      jwt.sign(signer)
      jwt.serialize()
    }

  def spec = suite("JWT")(
    asymmetricTests,
    symmetricTests,
    claimConversionTests,
    parseClaimsTests,
    signatureAlgorithmTests,
    headerTests,
  )

  /** Signs an arbitrary Nimbus claims set so claim values of Java types that zio-json would
    * never produce on its own (Integer, Short, BigDecimal, Date, ...) reach the decoder.
    */
  private def signRaw(build: com.nimbusds.jwt.JWTClaimsSet.Builder => com.nimbusds.jwt.JWTClaimsSet.Builder) =
    ZIO.attempt {
      val claims = build(
        com.nimbusds.jwt.JWTClaimsSet.Builder()
          .jwtID("jti-1")
          .expirationTime(java.util.Date.from(java.time.Instant.parse("2100-01-01T00:00:00Z"))),
      ).build()
      val header = com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
        .keyID("test-key-1")
        .`type`(com.nimbusds.jose.JOSEObjectType.JWT)
        .build()
      val jwt = com.nimbusds.jwt.SignedJWT(header, claims)
      jwt.sign(com.nimbusds.jose.crypto.RSASSASigner(privateKey))
      jwt.serialize()
    }

  private def claimJson(build: com.nimbusds.jwt.JWTClaimsSet.Builder => com.nimbusds.jwt.JWTClaimsSet.Builder) =
    signRaw(build).flatMap(JWT.deserialize[Json.Obj](_, publicKeys, JWT.Type.JWT))

  private val claimConversionTests = suite("claim conversion")(
    test("converts string, boolean and null claims") {
      for claims <- claimJson(_
          .claim("s", "text")
          .claim("bool", java.lang.Boolean.TRUE)
          .claim("nothing", null))
      yield assertTrue(
        claims.get("s") == Some(Json.Str("text")),
        claims.get("bool") == Some(Json.Bool(true)),
        claims.get("nothing").forall(_ == Json.Null),
      )
    },
    test("converts every integral number type to a JSON number") {
      for claims <- claimJson(_
          .claim("int", java.lang.Integer.valueOf(1))
          .claim("long", java.lang.Long.valueOf(2L))
          .claim("short", java.lang.Short.valueOf(3.toShort))
          .claim("byte", java.lang.Byte.valueOf(4.toByte))
          .claim("bigint", java.math.BigInteger.valueOf(5L)))
      yield assertTrue(
        claims.get("int") == Some(Json.Num(1)),
        claims.get("long") == Some(Json.Num(2)),
        claims.get("short") == Some(Json.Num(3)),
        claims.get("byte") == Some(Json.Num(4)),
        claims.get("bigint") == Some(Json.Num(BigDecimal(5))),
      )
    },
    test("converts every fractional number type to a JSON number") {
      for claims <- claimJson(_
          .claim("double", java.lang.Double.valueOf(1.5d))
          .claim("float", java.lang.Float.valueOf(2.5f))
          .claim("bigdec", java.math.BigDecimal.valueOf(3.5d)))
      yield assertTrue(
        claims.get("double") == Some(Json.Num(1.5d)),
        claims.get("float") == Some(Json.Num(2.5d)),
        claims.get("bigdec") == Some(Json.Num(BigDecimal(3.5d))),
      )
    },
    test("converts a date claim to epoch seconds") {
      val instant = java.time.Instant.parse("2024-01-01T00:00:00Z")
      for claims <- claimJson(_.claim("issued", java.util.Date.from(instant)))
      yield assertTrue(claims.get("issued") == Some(Json.Num(instant.getEpochSecond)))
    },
    test("converts nested maps and lists") {
      for claims <- claimJson(_
          .claim("map", java.util.Map.of("inner", "value"))
          .claim("list", java.util.List.of("a", "b")))
      yield assertTrue(
        claims.get("map") == Some(Json.Obj("inner" -> Json.Str("value"))),
        claims.get("list") == Some(Json.Arr(Json.Str("a"), Json.Str("b"))),
      )
    },
    test("falls back to the string form for any other claim type") {
      for claims <- claimJson(_.claim("uri", java.net.URI.create("https://example.com")))
      yield assertTrue(claims.get("uri") == Some(Json.Str("https://example.com")))
    },
  )

  private val parseClaimsTests = suite("parseClaims")(
    test("reads the payload without verifying the signature") {
      val claims = JWT.Claims(
        issuer = "test-issuer",
        subject = "user123",
        audience = List("api"),
        custom = Json.Obj("name" -> Json.Str("Test User"), "admin" -> Json.Bool(true)),
      )
      for
        token <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "test-key-1", privateKey),
        )
        tampered = token.split('.').nn.updated(2, "not-a-signature").mkString(".")
        result <- JWT.parseClaims[TestClaims](tampered)
      yield assertTrue(result.sub == "user123", result.admin)
    },
    test("fails with InvalidClaims when the payload is not the expected shape") {
      for result <- JWT.parseClaims[TestClaims]("a.bm90LWpzb24.c").either
      yield assertTrue(result == Left(JWT.Error.InvalidClaims))
    },
  )

  def asymmetricTests = suite("asymmetric signature")(
    test("serialize and deserialize successfully") {
      val claims = JWT.Claims(
        issuer = "test-issuer",
        subject = "user123",
        audience = List("api"),
        custom = Json.Obj("name" -> Json.Str("Test User"), "admin" -> Json.Bool(false)),
      )

      for
        token <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "test-key-1", privateKey),
        )
        result <- JWT.deserialize[TestClaims](token, publicKeys, JWT.Type.JWT)
      yield assertTrue(
        result.sub == "user123",
        result.name == "Test User",
        result.admin == false,
      )
    },
    test("fail with Expired for expired token using TestClock") {
      val claims = JWT.Claims(
        issuer = "test-issuer",
        subject = "user123",
        audience = List("api"),
        custom = Json.Obj("name" -> Json.Str("Test User"), "admin" -> Json.Bool(false)),
      )

      for
        token <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "test-key-1", privateKey),
        )
        _ <- TestClock.adjust(2.hours)
        result <- JWT.deserialize[TestClaims](token, publicKeys, JWT.Type.JWT).either
      yield assertTrue(result.left.exists { case _: JWT.Error.Expired => true; case _ => false })
    },
    test("serializes nested-object and null custom claims") {
      val claims = JWT.Claims(
        issuer = "test-issuer",
        subject = "user123",
        audience = List("api"),
        custom = Json.Obj("meta" -> Json.Obj("k" -> Json.Str("v")), "nothing" -> Json.Null),
      )

      for
        token <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "test-key-1", privateKey),
        )
        result <- JWT.deserialize[Json.Obj](token, publicKeys, JWT.Type.JWT)
      yield assertTrue(
        result.get("meta") == Some(Json.Obj("k" -> Json.Str("v"))),
        result.get("nothing").forall(_ == Json.Null),
      )
    },
    test("verifies against an EC key served from the JWKS (not just RSA)") {
      for
        token <- signRawWithHeader(
          "ec-key-1",
          com.nimbusds.jose.JWSAlgorithm.ES256,
          com.nimbusds.jose.crypto.ECDSASigner(ecKeyPair.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]),
        )(_.subject("user123"))
        result <- JWT.deserialize[Json.Obj](token, ecPublicKeys, JWT.Type.JWT)
      yield assertTrue(result.get("sub") == Some(Json.Str("user123")))
    },
    test("verifies against an octet-sequence key served from the JWKS (not passed directly)") {
      for
        token <- signRawWithHeader(
          "hmac-key-1",
          com.nimbusds.jose.JWSAlgorithm.HS256,
          com.nimbusds.jose.crypto.MACSigner(symmetricKey),
        )(_.subject("user123"))
        result <- JWT.deserialize[Json.Obj](token, octetPublicKeys, JWT.Type.JWT)
      yield assertTrue(result.get("sub") == Some(Json.Str("user123")))
    },
  )

  private val signatureAlgorithmTests = suite("signature/algorithm mismatch")(
    test("fails to serialize when an asymmetric signature declares a non-RS256 algorithm") {
      val claims = JWT.Claims("test-issuer", "user123", List("api"), Json.Obj())
      for result <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.HS256, "test-key-1", privateKey),
        ).exit
      yield assertTrue(result.isFailure)
    },
  )

  private val headerTests = suite("Header")(
    test("get returns the custom param when present and None otherwise") {
      val header = com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
        .customParam("eid", "edge-1")
        .build()
      val jwtHeader = JWT.Header(header)
      assertTrue(
        jwtHeader.get("eid") == Some("edge-1"),
        jwtHeader.get("missing") == None,
      )
    },
  )

  def symmetricTests = suite("symmetric signature")(
    test("serialize and deserialize successfully") {
      val claims = JWT.Claims(
        issuer = "test-issuer",
        subject = "user123",
        audience = List("api"),
        custom = Json.Obj("name" -> Json.Str("Test User"), "admin" -> Json.Bool(false)),
      )

      for
        token <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Symmetric(symmetricKey),
        )
        result <- JWT.deserialize[TestClaims](token, symmetricKey, JWT.Type.JWT)
      yield assertTrue(
        result.sub == "user123",
        result.name == "Test User",
        result.admin == false,
      )
    },
    test("fail with Expired for expired token using TestClock") {
      val claims = JWT.Claims(
        issuer = "test-issuer",
        subject = "user123",
        audience = List("api"),
        custom = Json.Obj("name" -> Json.Str("Test User"), "admin" -> Json.Bool(false)),
      )

      for
        token <- JWT.serialize(
          claims = claims,
          ttl = 1.hour,
          signature = JWT.Signature.Symmetric(symmetricKey),
        )
        _ <- TestClock.adjust(2.hours)
        result <- JWT.deserialize[TestClaims](token, symmetricKey, JWT.Type.JWT).either
      yield assertTrue(result.left.exists { case _: JWT.Error.Expired => true; case _ => false })
    },
  )
