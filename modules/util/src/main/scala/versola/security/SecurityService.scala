package versola.security

import org.apache.commons.codec.digest.Blake3
import zio.{Task, URLayer, ZIO, ZLayer}

import javax.crypto.spec.GCMParameterSpec
import javax.crypto.{Cipher, SecretKey}

trait SecurityService:
  def encryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]
  def decryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]

  def macBlake3(secret: Secret, key: Array[Byte]): Task[MAC]

object SecurityService:
  def live: URLayer[SecureRandom, SecurityService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(secureRandom: SecureRandom) extends SecurityService:

    private val Algorithm = "AES"
    private val Transformation = "AES/GCM/NoPadding"
    private val GcmIvLength = 12
    private val GcmTagLength = 16

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

    override def macBlake3(data: Secret, key: Array[Byte]): Task[MAC] =
      ZIO.succeed:
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(key)
          .update(data)
          .doFinalize(mac)
        MAC(mac)
        
    
