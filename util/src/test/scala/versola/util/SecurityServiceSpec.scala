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

    test("encryptRsa with public key round-trips via decryptRsa with private key") {
      val secret = "client-secret-payload".getBytes("UTF-8")

      for
        service   <- ZIO.service[SecurityService]
        keyPair   <- service.generateRsaKeyPair
        encrypted <- service.encryptRsa(secret, keyPair.publicKey)
        decrypted <- service.decryptRsa(encrypted, keyPair.privateKey)
      yield assertTrue(
        !encrypted.sameElements(secret),
        decrypted.sameElements(secret),
      )
    }.provide(SecurityService.live, SecureRandom.live),

    // The regression this guards: a synced edgeSigningKey's JWK document routinely exceeds
    // this, and did until encryptRsaHybrid existed -- `configuration/clients/sync` 500'd with
    // an uncaught IllegalBlockSizeException for any client that registered one.
    test("encryptRsa refuses data past the RSA-OAEP-SHA256 bound a 2048-bit key allows") {
      val tooLong = Array.fill(191)(1.toByte)

      for
        service <- ZIO.service[SecurityService]
        keyPair <- service.generateRsaKeyPair
        result <- service.encryptRsa(tooLong, keyPair.publicKey).exit
      yield assertTrue(result.isFailure)
    }.provide(SecurityService.live, SecureRandom.live),

    test("encryptRsaHybrid round-trips data past the plain encryptRsa bound via decryptRsaHybrid") {
      val tooLong = Array.tabulate(191)(_.toByte)

      for
        service   <- ZIO.service[SecurityService]
        keyPair   <- service.generateRsaKeyPair
        encrypted <- service.encryptRsaHybrid(tooLong, keyPair.publicKey)
        decrypted <- service.decryptRsaHybrid(encrypted, keyPair.privateKey)
      yield assertTrue(
        !encrypted.sameElements(tooLong),
        decrypted.sameElements(tooLong),
      )
    }.provide(SecurityService.live, SecureRandom.live),

    test("encryptRsaHybrid also round-trips data well within the plain encryptRsa bound") {
      val secret = "short session secret".getBytes("UTF-8")

      for
        service   <- ZIO.service[SecurityService]
        keyPair   <- service.generateRsaKeyPair
        encrypted <- service.encryptRsaHybrid(secret, keyPair.publicKey)
        decrypted <- service.decryptRsaHybrid(encrypted, keyPair.privateKey)
      yield assertTrue(decrypted.sameElements(secret))
    }.provide(SecurityService.live, SecureRandom.live),

    test("generateEcKeyPair produces a P-256 key pair published as ES256") {
      for
        service <- ZIO.service[SecurityService]
        keyPair <- service.generateEcKeyPair
      yield
        val fieldsMap = keyPair.toPublicJwk.fields.toMap
        val timestampPattern = "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}".r

        assertTrue(
          keyPair.publicKey.getAlgorithm == "EC",
          keyPair.privateKey.getAlgorithm == "EC",
          // ES256 is defined on P-256 only, so the curve is part of the contract.
          keyPair.publicKey.getParams.getCurve.getField.getFieldSize == 256,
          timestampPattern.matches(keyPair.keyId),
          keyPair.keyId.length == 19,
          fieldsMap.get("kty") == Some(zio.json.ast.Json.Str("EC")),
          fieldsMap.get("crv") == Some(zio.json.ast.Json.Str("P-256")),
          fieldsMap.get("alg") == Some(zio.json.ast.Json.Str("ES256")),
          fieldsMap.get("use") == Some(zio.json.ast.Json.Str("sig")),
          fieldsMap.contains("x"),
          fieldsMap.contains("y"),
          // The private half must never reach the published JWK.
          !fieldsMap.contains("d"),
        )
    }.provide(SecurityService.live, SecureRandom.live),

    test("generated EC keys can sign and verify data") {
      val originalData = "Hello, World! This is a test message.".getBytes("UTF-8")

      for
        service <- ZIO.service[SecurityService]
        keyPair <- service.generateEcKeyPair
      yield
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.privateKey)
        signer.update(originalData)
        val signature = signer.sign()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.publicKey)
        verifier.update(originalData)

        assertTrue(verifier.verify(signature))
    }.provide(SecurityService.live, SecureRandom.live),
  )
