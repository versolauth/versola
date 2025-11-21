package versola.util

import com.fasterxml.uuid.{Generators, UUIDClock}
import zio.{Clock, FiberRef, Task, UIO, ULayer, ZLayer}

import java.security.SecureRandom as JSecureRandom
import java.util.UUID

trait SecureRandom:
  def nextBytes(length: Int): Task[Array[Byte]]
  def nextHex(length: Int): Task[String]
  def nextNumeric(length: Int): Task[String]
  def nextAlphanumeric(length: Int): Task[String]
  def nextUUIDv7: Task[UUID]
  def setSeed(seed: Long): UIO[Unit]
  def execute[A](fn: JSecureRandom => A): Task[A]

object SecureRandom:
  private val Numeric = "0123456789"
  private val Alphanumeric = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  private val Hex = "0123456789abcdef"

  def live: ULayer[SecureRandom] = ZLayer.scoped:
    for
      randomRef <- FiberRef.make(JSecureRandom.getInstanceStrong)
      _ <- randomRef.get.map(_.nextLong())
      clock <- Clock.javaClock
      uuidClock = new UUIDClock:
        override def currentTimeMillis(): Long = clock.millis()
    yield Impl(uuidClock, randomRef)

  private final class Impl(
      uuidClock: UUIDClock,
      randomRef: FiberRef[JSecureRandom],
  ) extends SecureRandom:

    override def nextBytes(length: Int): Task[Array[Byte]] =
      randomRef.get.map { r =>
        val array = Array.ofDim[Byte](length)
        r.nextBytes(array)
        array
      }

    override def nextUUIDv7: Task[UUID] =
      randomRef.get.map(Generators.timeBasedEpochGenerator(_, uuidClock).generate())

    override def nextHex(length: Int): Task[String] =
      nextAlphabetString(alphabet = Hex, length = length)

    override def nextNumeric(length: Int): Task[String] =
      nextAlphabetString(alphabet = Numeric, length = length)

    override def nextAlphanumeric(length: Int): Task[String] =
      nextAlphabetString(alphabet = Alphanumeric, length = length)

    override def setSeed(seed: Long): UIO[Unit] =
      randomRef.get.map(_.setSeed(seed))

    override def execute[A](fn: JSecureRandom => A): Task[A] =
      randomRef.get.map(fn)

    private def nextAlphabetString(alphabet: String, length: Int): Task[String] =
      randomRef.get.map: random =>
        val sb = StringBuilder(length)
        (1 to length).foreach(_ => sb.append(alphabet(random.nextInt(alphabet.length))))
        sb.toString()
