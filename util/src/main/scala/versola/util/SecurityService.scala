package versola.util

import org.apache.commons.codec.digest.Blake3
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import zio.{Clock, Semaphore, Task, UIO, URLayer, ZIO, ZLayer}

import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, PrivateKey, PublicKey}
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKey}

trait SecurityService:
  def encryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]
  def decryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]

  def encryptRsa(data: Array[Byte], key: PublicKey): Task[Array[Byte]]
  def decryptRsa(data: Array[Byte], key: PrivateKey): Task[Array[Byte]]

  /** RSA-OAEP-SHA256 caps a single [[encryptRsa]] call at `keySizeBytes - 66` bytes -- 190 for
    * the 2048-bit keys [[generateRsaKeyPair]] issues -- so anything registration lets grow
    * past that (an edge signing key's full JWK document, say) throws
    * `IllegalBlockSizeException` rather than a typed failure a caller could react to.
    *
    * Wraps a fresh AES-256 key with [[encryptRsa]] (32 bytes, always within the limit) and
    * encrypts the actual payload under it with [[encryptAes256]], so the RSA bound never
    * applies to the caller's data at all. Composed from the four abstract members above, not
    * a fifth one of its own -- an implementer that already satisfies this trait needs no
    * change to pick this up.
    */
  def encryptRsaHybrid(data: Array[Byte], key: PublicKey): Task[Array[Byte]] =
    for
      sessionKeyBytes <- ZIO.attemptBlocking:
        val bytes = new Array[Byte](32)
        new java.security.SecureRandom().nextBytes(bytes)
        bytes
      wrappedKey <- encryptRsa(sessionKeyBytes, key)
      encryptedPayload <- encryptAes256(data, new SecretKeySpec(sessionKeyBytes, "AES"))
    yield wrappedKey ++ encryptedPayload

  /** The [[encryptRsaHybrid]] counterpart: splits at the RSA modulus size (deterministic from
    * `key` alone, so no length has to be carried on the wire), unwraps the session key with
    * [[decryptRsa]], then [[decryptAes256]]s the remainder under it.
    */
  def decryptRsaHybrid(data: Array[Byte], key: PrivateKey): Task[Array[Byte]] =
    for
      rsaKeySize <- ZIO.attempt(key.asInstanceOf[RSAPrivateKey].getModulus.bitLength / 8)
      _ <- ZIO.attempt {
        if data.length <= rsaKeySize then
          throw new IllegalArgumentException(
            s"hybrid ciphertext of ${data.length} bytes is no longer than the $rsaKeySize-byte wrapped key",
          )
      }
      (wrappedKey, encryptedPayload) = data.splitAt(rsaKeySize)
      sessionKeyBytes <- decryptRsa(wrappedKey, key)
      payload <- decryptAes256(encryptedPayload, new SecretKeySpec(sessionKeyBytes, "AES"))
    yield payload

  def mac(secret: Secret, key: Array[Byte]): Task[MAC]

  def hashPassword(password: Secret, salt: Salt, pepper: Secret.Bytes16): Task[MAC]

  def generateRsaKeyPair: UIO[RsaKeyPair]

  /** A P-256 keypair, the only curve [[JWT.Algorithm.ES256]] signs on. */
  def generateEcKeyPair: UIO[EcKeyPair]

object SecurityService:
  /** Used by services that never hash passwords (central, edge); auth passes its configured
    * `Argon2Config`.
    */
  def live: URLayer[SecureRandom, SecurityService] =
    live(Argon2Config.default)

  def live(argon2Config: Argon2Config): URLayer[SecureRandom, SecurityService] =
    ZLayer.fromZIO {
      for
        secureRandom <- ZIO.service[SecureRandom]
        hashingSemaphore <- Semaphore.make(argon2Config.maxConcurrent.toLong)
      yield Impl(secureRandom, hashingSemaphore)
    }

  class Impl(secureRandom: SecureRandom, hashingSemaphore: Semaphore) extends SecurityService:

    private val Algorithm = "AES"
    private val Transformation = "AES/GCM/NoPadding"
    private val RsaTransformation = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
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

    override def encryptRsa(data: Array[Byte], key: PublicKey): Task[Array[Byte]] =
      ZIO.attemptBlocking:
        val cipher = Cipher.getInstance(RsaTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.doFinal(data)

    override def decryptRsa(data: Array[Byte], key: PrivateKey): Task[Array[Byte]] =
      ZIO.attemptBlocking:
        val cipher = Cipher.getInstance(RsaTransformation)
        cipher.init(Cipher.DECRYPT_MODE, key)
        cipher.doFinal(data)

    override def mac(data: Secret, key: Array[Byte]): Task[MAC] =
      ZIO.succeed:
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(key)
          .update(data)
          .doFinalize(mac)
        MAC(mac)

    override def hashPassword(password: Secret, salt: Salt, pepper: Secret.Bytes16): Task[MAC] =
      val hashEffect = ZIO.attemptBlocking {
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
      }
      // Admission control: Argon2id holds ~19 MiB of heap per in-flight hash, so unbounded
      // concurrency (this runs on ZIO's unbounded blocking pool) is an OOM vector under login
      // load. The semaphore caps concurrent hashes; excess requests queue as cheap fibers
      // instead of each claiming 19 MiB up front.
      hashingSemaphore.withPermit(hashEffect)

    override def generateRsaKeyPair: UIO[RsaKeyPair] =
      for
        now <- Clock.instant
        keyPair <- ZIO.succeedBlocking:
          val gen = KeyPairGenerator.getInstance("RSA")
          gen.initialize(2048)
          gen.generateKeyPair()
      yield
        val publicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]
        val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]

        // Format timestamp as YYYY-MM-DD_HH-MM-SS (up to seconds precision)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val keyId = formatter.format(now.atZone(ZoneOffset.UTC))

        RsaKeyPair(
          keyId = keyId,
          publicKey = publicKey,
          privateKey = privateKey,
        )

    override def generateEcKeyPair: UIO[EcKeyPair] =
      for
        now <- Clock.instant
        keyPair <- ZIO.succeedBlocking:
          val gen = KeyPairGenerator.getInstance("EC")
          gen.initialize(ECGenParameterSpec("secp256r1"))
          gen.generateKeyPair()
      yield
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        EcKeyPair(
          keyId = formatter.format(now.atZone(ZoneOffset.UTC)),
          publicKey = keyPair.getPublic.asInstanceOf[ECPublicKey],
          privateKey = keyPair.getPrivate.asInstanceOf[ECPrivateKey],
        )
