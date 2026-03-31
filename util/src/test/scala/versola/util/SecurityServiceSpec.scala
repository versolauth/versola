package versola.util

import zio.test.*
import zio.ZIO

import java.security.Signature

object SecurityServiceSpec extends ZIOSpecDefault:

  def spec = suite("SecurityService")(
    test("generateRsaKeyPair produces valid 2048-bit RSA key pair with timestamp-based ID") {
      for
        service <- ZIO.service[SecurityService]
        keyPair <- service.generateRsaKeyPair
      yield
        val jwk = keyPair.toPublicJwk
        val fieldsMap = jwk.fields.toMap
        val timestampPattern = "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}".r

        assertTrue(
          // Key properties
          keyPair.publicKey.getAlgorithm == "RSA",
          keyPair.privateKey.getAlgorithm == "RSA",
          keyPair.publicKey.getModulus.bitLength() == 2048,
          // Key ID format (YYYY-MM-DD_HH-MM-SS)
          timestampPattern.matches(keyPair.keyId),
          keyPair.keyId.length == 19,
          // JWK structure
          fieldsMap.contains("kty"),
          fieldsMap.contains("kid"),
          fieldsMap.contains("alg"),
          fieldsMap.contains("use"),
          fieldsMap.contains("n"), // modulus
          fieldsMap.contains("e"), // exponent
        )
    }.provide(SecurityService.live, SecureRandom.live),

    test("generated RSA keys can sign and verify data") {
      val originalData = "Hello, World! This is a test message.".getBytes("UTF-8")

      for
        service <- ZIO.service[SecurityService]
        keyPair <- service.generateRsaKeyPair
        // Sign data with private key
        signature <- ZIO.attemptBlocking {
          val signer = Signature.getInstance("SHA256withRSA")
          signer.initSign(keyPair.privateKey)
          signer.update(originalData)
          signer.sign()
        }
        // Verify signature with public key
        verified <- ZIO.attemptBlocking {
          val verifier = Signature.getInstance("SHA256withRSA")
          verifier.initVerify(keyPair.publicKey)
          verifier.update(originalData)
          verifier.verify(signature)
        }
        // Verify that wrong data fails verification
        wrongVerified <- ZIO.attemptBlocking {
          val verifier = Signature.getInstance("SHA256withRSA")
          verifier.initVerify(keyPair.publicKey)
          verifier.update("Wrong data".getBytes("UTF-8"))
          verifier.verify(signature)
        }
      yield assertTrue(
        signature.length > 0,
        verified == true,
        wrongVerified == false,
      )
    }.provide(SecurityService.live, SecureRandom.live),
  )
