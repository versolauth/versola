package versola.util

import zio.*
import zio.test.*

import java.security.KeyPairGenerator
import java.util.Base64

object SecretConfigSpec extends ZIOSpecDefault:

  import SecretConfigChecks.{longMarker, marker}

  private def message[A](result: Either[Config.Error.InvalidData, A]): Either[String, A] =
    result.left.map(_.message)

  private val rsaKey: String =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    Base64.getEncoder.encodeToString(generator.generateKeyPair().getPrivate.getEncoded)

  private val ecKey: String =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    Base64.getEncoder.encodeToString(generator.generateKeyPair().getPrivate.getEncoded)

  def spec = suite("SecretConfig")(
    suite("base64Url")(
      test("accepts a value with and without `=` padding") {
        val bytes = Array.tabulate[Byte](16)(_.toByte)
        val padded = Base64.getUrlEncoder.encodeToString(bytes)
        val unpadded = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
        assertTrue(
          padded.endsWith("="),
          SecretConfig.base64Url(padded).map(_.toSeq) == Right(bytes.toSeq),
          SecretConfig.base64Url(unpadded).map(_.toSeq) == Right(bytes.toSeq),
        )
      },
      test("reports an illegal character as a fixed reason, without the decoder's message") {
        assertTrue(
          message(SecretConfig.base64Url(marker + "+/")) == Left("not valid base64url"),
          message(SecretConfig.base64Url(marker + "$")) == Left("not valid base64url"),
          // A lone character cannot be decoded at all; `java.util.Base64` reports it differently again.
          message(SecretConfig.base64Url("S")) == Left("not valid base64url"),
        )
      },
    ),
    suite("fixedLength")(
      test("accepts exactly the expected number of bytes") {
        assertTrue(
          SecretConfig.fixedLength(Secret.Bytes16)(marker).map(_.length) == Right(16),
          SecretConfig.fixedLength(Secret.Bytes32)(Base64.getUrlEncoder.encodeToString(Array.fill(32)(1.toByte))).map(_.length) == Right(32),
        )
      },
      test("reports the expected and the actual length, and not the value") {
        assertTrue(
          message(SecretConfig.fixedLength(Secret.Bytes32)(marker)) == Left("expected 32 bytes after base64url decoding, got 16"),
          message(SecretConfig.fixedLength(Secret.Bytes16)(longMarker)) == Left("expected 16 bytes after base64url decoding, got 33"),
          message(SecretConfig.fixedLength(Secret.Bytes32)(marker + "+")) == Left("not valid base64url"),
        )
      },
    ),
    suite("secret")(
      test("decodes any length and rejects what is not base64url") {
        assertTrue(
          SecretConfig.secret(longMarker).map(_.length) == Right(33),
          message(SecretConfig.secret(marker + "+/")) == Left("not valid base64url"),
        )
      },
    ),
    suite("rsaPrivateKey")(
      test("accepts a base64-encoded PKCS#8 key") {
        assertTrue(SecretConfig.rsaPrivateKey(rsaKey).map(_.getAlgorithm) == Right("RSA"))
      },
      test("reports a fixed reason for base64 that is not a key and for an illegal character") {
        assertTrue(
          message(SecretConfig.rsaPrivateKey(longMarker)) == Left("not a base64-encoded PKCS#8 RSA private key"),
          message(SecretConfig.rsaPrivateKey(marker + "-")) == Left("not a base64-encoded PKCS#8 RSA private key"),
        )
      },
    ),
    suite("signingPrivateKey")(
      test("accepts a base64-encoded PKCS#8 RSA key, as before") {
        assertTrue(SecretConfig.signingPrivateKey(rsaKey).map(_.getAlgorithm) == Right("RSA"))
      },
      test("accepts a base64-encoded PKCS#8 EC key") {
        assertTrue(SecretConfig.signingPrivateKey(ecKey).map(_.getAlgorithm) == Right("EC"))
      },
      test("reports a fixed reason for what is not a key") {
        val reason = "not a base64-encoded PKCS#8 RSA or EC private key"
        assertTrue(
          message(SecretConfig.signingPrivateKey(longMarker)) == Left(reason),
          message(SecretConfig.signingPrivateKey(marker + "-")) == Left(reason),
        )
      },
    ),
  )
