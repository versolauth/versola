package versola.util

import zio.*
import zio.test.*

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object CryptoServiceSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CryptoServiceSpec")(
      encryptDecryptTests,
      errorHandlingTests,
    ).provide(SecureRandom.live >>> ZLayer.fromFunction(CryptoService.Impl(_)))

  private def encryptDecryptTests =
    suite("encrypt/decrypt")(
      test("encrypt and decrypt data successfully") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key = createTestKey()
          originalData = "Hello, World!".getBytes("UTF-8")
          encrypted <- cryptoService.encryptAes256(originalData, key)
          decrypted <- cryptoService.decryptAes256(encrypted, key)
        yield assertTrue(
          !originalData.sameElements(encrypted), // Data should be encrypted
          originalData.sameElements(decrypted), // Decrypted should match original
        )
      },
      test("encrypt empty data") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key = createTestKey()
          originalData = Array.empty[Byte]
          encrypted <- cryptoService.encryptAes256(originalData, key)
          decrypted <- cryptoService.decryptAes256(encrypted, key)
        yield assertTrue(
          encrypted.nonEmpty, // Even empty data produces non-empty encrypted result (IV + tag)
          originalData.sameElements(decrypted),
        )
      },
      test("encrypt large data") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key = createTestKey()
          originalData = Array.fill(1024)('A'.toByte)
          encrypted <- cryptoService.encryptAes256(originalData, key)
          decrypted <- cryptoService.decryptAes256(encrypted, key)
        yield assertTrue(
          originalData.sameElements(decrypted)
        )
      },
      test("different encryptions of same data produce different results") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key = createTestKey()
          originalData = "Same data".getBytes("UTF-8")
          encrypted1 <- cryptoService.encryptAes256(originalData, key)
          encrypted2 <- cryptoService.encryptAes256(originalData, key)
          decrypted1 <- cryptoService.decryptAes256(encrypted1, key)
          decrypted2 <- cryptoService.decryptAes256(encrypted2, key)
        yield assertTrue(
          !encrypted1.sameElements(encrypted2), // Different IVs should produce different results
          originalData.sameElements(decrypted1),
          originalData.sameElements(decrypted2),
        )
      },
    )

  private def errorHandlingTests =
    suite("error handling")(
      test("decrypt fails with wrong key") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key1 = createTestKey()
          key2 = createTestKey(seed = 42)
          originalData = "Secret data".getBytes("UTF-8")
          encrypted <- cryptoService.encryptAes256(originalData, key1)
          result <- cryptoService.decryptAes256(encrypted, key2).exit
        yield assertTrue(result.isFailure)
      },
      test("decrypt fails with corrupted data") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key = createTestKey()
          originalData = "Secret data".getBytes("UTF-8")
          encrypted <- cryptoService.encryptAes256(originalData, key)
          corruptedData = encrypted.updated(encrypted.length / 2, (encrypted(encrypted.length / 2) ^ 0xFF).toByte)
          result <- cryptoService.decryptAes256(corruptedData, key).exit
        yield assertTrue(result.isFailure)
      },
      test("decrypt fails with too short data") {
        for
          cryptoService <- ZIO.service[CryptoService]
          key = createTestKey()
          shortData = Array.fill(10)(0x42.toByte) // Less than IV + tag length
          result <- cryptoService.decryptAes256(shortData, key).exit
        yield assertTrue(result.isFailure)
      },
    )

  private def createTestKey(seed: Int = 1): SecretKey =
    new SecretKeySpec(Array.fill(32)(seed.toByte), "AES")
