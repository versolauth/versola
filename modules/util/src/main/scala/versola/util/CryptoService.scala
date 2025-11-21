package versola.util

import zio.{Task, ULayer, URLayer, ZIO, ZLayer}

import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKey}
import java.security.SecureRandom as JSecureRandom

trait CryptoService:
  def encryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]
  def decryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]]

object CryptoService:
  def live: URLayer[SecureRandom, CryptoService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(secureRandom: SecureRandom) extends CryptoService:

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
