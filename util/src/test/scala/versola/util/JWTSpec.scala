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
        .build()
    )
  )

  // Test symmetric key
  private val keyGenerator = KeyGenerator.getInstance("HmacSHA256")
  keyGenerator.init(256)
  private val symmetricKey = keyGenerator.generateKey()

  // Test claims
  case class TestClaims(sub: String, name: String, admin: Boolean) derives JsonCodec

  def spec = suite("JWT")(
    asymmetricTests,
    symmetricTests,
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
        result <- JWT.deserialize[TestClaims](token, symmetricKey)
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
        result <- JWT.deserialize[TestClaims](token, symmetricKey).either
      yield assertTrue(result.left.exists { case _: JWT.Error.Expired => true; case _ => false })
    },
  )
