package versola.util

import org.apache.commons.codec.digest.Blake3
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import zio.{Task, URLayer, ZIO, ZLayer}

import javax.crypto.spec.GCMParameterSpec
import javax.crypto.{Cipher, SecretKey}

trait SecurityService:
  def encryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]
  def decryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]

  def mac(secret: Secret, key: Array[Byte]): Task[MAC]

  def hashPassword(password: Secret, salt: Salt, pepper: Secret.Bytes16): Task[MAC]

object SecurityService:
  def live: URLayer[SecureRandom, SecurityService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(secureRandom: SecureRandom) extends SecurityService:

    private val Algorithm = "AES"
    private val Transformation = "AES/GCM/NoPadding"
    private val GcmIvLength = 12
    private val GcmTagLength = 16

    // Argon2id parameters following OWASP recommendations
    // Memory: 19 MiB (19456 KiB), Iterations: 2, Parallelism: 1
    private val Argon2MemoryKiB = 19456
    private val Argon2Iterations = 2
    private val Argon2Parallelism = 1
    private val Argon2HashLength = 32 // 32 bytes = 256 bits

    override def encryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]] =
      ZIO.blocking:
        for
          iv <- ZIO.succeed(new Array[Byte](GcmIvLength))
          _ <- secureRandom.execute(_.nextBytes(iv))
          gcmSpec = new GCMParameterSpec(GcmTagLength * 8, iv)
          encrypted <- ZIO.attempt:
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
            cipher.doFinal(data)
        yield iv ++ encrypted

    override def decryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]] =
      ZIO.attemptBlocking {
        if data.length < GcmIvLength + GcmTagLength then
          throw new IllegalArgumentException("Invalid encrypted data length")

        val cipher = Cipher.getInstance(Transformation)

        val iv = data.take(GcmIvLength)
        val encrypted = data.drop(GcmIvLength)

        val gcmSpec = new GCMParameterSpec(GcmTagLength * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        cipher.doFinal(encrypted)
      }

    override def mac(data: Secret, key: Array[Byte]): Task[MAC] =
      ZIO.succeed:
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(key)
          .update(data)
          .doFinalize(mac)
        MAC(mac)

    override def hashPassword(password: Secret, salt: Salt, pepper: Secret.Bytes16): Task[MAC] =
      ZIO.attemptBlocking:
        // Combine salt and pepper as additional data
        val params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
          .withVersion(Argon2Parameters.ARGON2_VERSION_13)
          .withIterations(Argon2Iterations)
          .withMemoryAsKB(Argon2MemoryKiB)
          .withParallelism(Argon2Parallelism)
          .withSalt(salt)
          .withAdditional(pepper)
          .build()

        val generator = new Argon2BytesGenerator()
        generator.init(params)

        val hash = Array.ofDim[Byte](Argon2HashLength)
        generator.generateBytes(password, hash)
        MAC(hash)