package versola.util

import com.fasterxml.uuid.{Generators, UUIDClock}
import zio.{Clock, UIO, ULayer, ZIO, ZLayer}

import java.security.SecureRandom as JSecureRandom
import java.util.UUID

trait SecureRandom:
  def nextBytes(length: Int): UIO[Array[Byte]]
  def nextHex(length: Int): UIO[String]
  def nextNumeric(length: Int): UIO[String]
  def nextAlphanumeric(length: Int): UIO[String]
  def nextUUIDv7: UIO[UUID]
  def setSeed(seed: Long): UIO[Unit]
  def execute[A](fn: JSecureRandom => A): UIO[A]

object SecureRandom:
  private val Numeric = "0123456789"
  private val Alphanumeric = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  private val Hex = "0123456789abcdef"

  def live: ULayer[SecureRandom] = ZLayer.scoped:
    for
      clock <- Clock.javaClock
      uuidClock = new UUIDClock:
        override def currentTimeMillis(): Long = clock.millis()
    yield Impl(uuidClock)

  private final class Impl(
      uuidClock: UUIDClock,
  ) extends SecureRandom:
    private val random = JSecureRandom.getInstanceStrong
    private val generator = Generators.timeBasedEpochGenerator(random, uuidClock)

    override def nextBytes(length: Int): UIO[Array[Byte]] =
      ZIO.succeed:
        val array = Array.ofDim[Byte](length)
        random.nextBytes(array)
        array

    override def nextUUIDv7: UIO[UUID] =
      ZIO.succeed:
        generator.generate()

    override def nextHex(length: Int): UIO[String] =
      nextAlphabetString(alphabet = Hex, length = length)

    override def nextNumeric(length: Int): UIO[String] =
      nextAlphabetString(alphabet = Numeric, length = length)

    override def nextAlphanumeric(length: Int): UIO[String] =
      nextAlphabetString(alphabet = Alphanumeric, length = length)

    override def setSeed(seed: Long): UIO[Unit] =
      ZIO.succeed: 
        random.setSeed(seed)

    override def execute[A](fn: JSecureRandom => A): UIO[A] =
      ZIO.succeed:
        fn(random)

    private def nextAlphabetString(alphabet: String, length: Int): UIO[String] =
      ZIO.succeed:
        val sb = StringBuilder(length)
        (1 to length).foreach(_ => sb.append(alphabet(random.nextInt(alphabet.length))))
        sb.toString()
