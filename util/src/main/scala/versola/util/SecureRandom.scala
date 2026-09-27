package versola.util

import com.fasterxml.uuid.impl.TimeBasedEpochGenerator
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

  /** One (`SecureRandom`, UUIDv7 generator) pair per carrier thread, not one shared across every
    * fiber in the process. Two problems, not one: `getInstanceStrong` opts into Linux's
    * `NativePRNGBlocking`, which reads `/dev/random` and stalls when the pool runs low, and a
    * `SecureRandom` synchronizes its own `next`/`nextBytes` internally, so a single shared
    * instance also serializes every concurrent caller onto that one lock regardless of algorithm.
    * A thread-local plain `new JSecureRandom()` (the JDK's ordinary, non-"strong" default,
    * seeded from `/dev/urandom` on Linux -- not weaker for tokens and IDs that need
    * unpredictability, not multi-year key material) fixes both at once: no shared lock, no
    * blocking read. [[TimeBasedEpochGenerator]] takes its `Random` once at construction, so it is
    * cached alongside the instance that feeds it rather than rebuilt per call.
    */
  private final class Impl(
      uuidClock: UUIDClock,
  ) extends SecureRandom:
    private final case class PerThread(random: JSecureRandom, generator: TimeBasedEpochGenerator)

    private val perThread: ThreadLocal[PerThread] = ThreadLocal.withInitial: () =>
      val random = JSecureRandom()
      PerThread(random, Generators.timeBasedEpochGenerator(random, uuidClock))

    override def nextBytes(length: Int): UIO[Array[Byte]] =
      ZIO.succeed:
        val array = Array.ofDim[Byte](length)
        perThread.get().random.nextBytes(array)
        array

    override def nextUUIDv7: UIO[UUID] =
      ZIO.succeed:
        perThread.get().generator.generate()

    override def nextHex(length: Int): UIO[String] =
      nextAlphabetString(alphabet = Hex, length = length)

    override def nextNumeric(length: Int): UIO[String] =
      nextAlphabetString(alphabet = Numeric, length = length)

    override def nextAlphanumeric(length: Int): UIO[String] =
      nextAlphabetString(alphabet = Alphanumeric, length = length)

    override def setSeed(seed: Long): UIO[Unit] =
      ZIO.succeed:
        perThread.get().random.setSeed(seed)

    override def execute[A](fn: JSecureRandom => A): UIO[A] =
      ZIO.succeed:
        fn(perThread.get().random)

    private def nextAlphabetString(alphabet: String, length: Int): UIO[String] =
      ZIO.succeed:
        val random = perThread.get().random
        val sb = StringBuilder(length)
        (1 to length).foreach(_ => sb.append(alphabet(random.nextInt(alphabet.length))))
        sb.toString()
