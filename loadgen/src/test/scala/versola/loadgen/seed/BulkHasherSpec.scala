package versola.loadgen.seed

import versola.util.{Argon2Config, EcKeyPair, MAC, RsaKeyPair, Salt, SecureRandom, SecurityService, Secret}
import zio.*
import zio.prelude.EqualOps
import zio.test.*

import java.security.{PrivateKey, PublicKey}
import javax.crypto.SecretKey

/** The two properties of the seeder's cost centre: the parallelism is actually bounded, and the
  * hashes are the ones auth will recompute.
  */
object BulkHasherSpec extends ZIOSpecDefault:

  private val pepper: Secret.Bytes16 = Secret.Bytes16(Array.tabulate(16)(index => (index * 5 + 1).toByte))

  /** Counts concurrent hashes around a real [[SecurityService]]. A decorator rather than a stub
    * because the delegate has to be the real Argon2: the point of the boundedness test is the
    * heap each in-flight hash holds, which a stub would not hold.
    */
  private final class Counting(delegate: SecurityService, inFlight: Ref[Int], peak: Ref[Int]) extends SecurityService:
    override def hashPassword(password: Secret, salt: Salt, pepper: Secret.Bytes16): Task[MAC] =
      ZIO.acquireReleaseWith(
        inFlight.updateAndGet(_ + 1).flatMap(current => peak.update(math.max(_, current))),
      )(_ => inFlight.update(_ - 1))(_ => delegate.hashPassword(password, salt, pepper))

    override def encryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]] = delegate.encryptAes256(data, key)
    override def decryptAes256(data: Array[Byte], key: SecretKey): Task[Array[Byte]] = delegate.decryptAes256(data, key)
    override def encryptRsa(data: Array[Byte], key: PublicKey): Task[Array[Byte]] = delegate.encryptRsa(data, key)
    override def decryptRsa(data: Array[Byte], key: PrivateKey): Task[Array[Byte]] = delegate.decryptRsa(data, key)
    override def mac(secret: Secret, key: Array[Byte]): Task[MAC] = delegate.mac(secret, key)
    override def generateRsaKeyPair: UIO[RsaKeyPair] = delegate.generateRsaKeyPair
    override def generateEcKeyPair: UIO[EcKeyPair] = delegate.generateEcKeyPair

  private def fixture(parallelism: Int) =
    for
      // `maxConcurrent` deliberately above the hasher's own parallelism, so the bound this test
      // observes is the hasher's `withParallelism` and not the service's internal semaphore. In
      // production Seeder sets both from the same field; here they are separated so the test can
      // tell which one is doing the work.
      security <- (SecureRandom.live >>> SecurityService.live(Argon2Config(maxConcurrent = 64))).build
        .map(_.get[SecurityService])
      random <- SecureRandom.live.build.map(_.get[SecureRandom])
      inFlight <- Ref.make(0)
      peak <- Ref.make(0)
      counting = Counting(security, inFlight, peak)
    yield (BulkHasher(counting, random, pepper, parallelism), security, peak)

  def spec = suite("BulkHasher")(
    // Argon2id holds ~19 MiB per in-flight hash, so an unbounded `foreachPar` over a 10,000-user
    // batch is ~190 GiB of intent. The bound is the whole reason this class exists rather than a
    // bare `ZIO.foreachPar`.
    test("never runs more hashes at once than it was configured for") {
      ZIO.scoped:
        fixture(parallelism = 3).flatMap: (hasher, _, peak) =>
          for
            _ <- hasher.hashAll(Chunk.fromIterable((1L to 40L).map(id => id -> s"password-$id")))
            observed <- peak.get
          yield assertTrue(observed <= 3, observed >= 2)
    },
    // The bound has to be a real constraint, not an accident of the batch being small: with the
    // parallelism raised, the same batch must actually use it.
    test("uses the parallelism it is given, rather than serialising") {
      ZIO.scoped:
        fixture(parallelism = 8).flatMap: (hasher, _, peak) =>
          for
            _ <- hasher.hashAll(Chunk.fromIterable((1L to 40L).map(id => id -> s"password-$id")))
            observed <- peak.get
          yield assertTrue(observed > 3, observed <= 8)
    },
    test("returns one salted hash per user, and every salt differs") {
      ZIO.scoped:
        fixture(parallelism = 4).flatMap: (hasher, _, _) =>
          hasher.hashAll(Chunk.fromIterable((1L to 20L).map(id => id -> s"password-$id"))).map: hashed =>
            assertTrue(
              hashed.map(_.id).toSet == (1L to 20L).toSet,
              hashed.forall(_.salt.length == BulkHasher.SaltBytes),
              hashed.map(_.salt.toSeq).toSet.size == 20,
              hashed.map(_.hash.toSeq).toSet.size == 20,
            )
    },
    // The agreement that matters: `PasswordService.check` re-hashes the submitted plaintext with
    // the *stored* salt and compares. If that computation and this one ever diverge, every
    // seeded password login fails and nothing names the seeder as the cause.
    test("each hash is what SecurityService recomputes from the plaintext and the stored salt") {
      ZIO.scoped:
        fixture(parallelism = 4).flatMap: (hasher, security, _) =>
          for
            hashed <- hasher.hashAll(Chunk.single(7L -> "correct horse"))
            entry = hashed.head
            recomputed <- security.hashPassword(Secret.fromString("correct horse"), entry.salt, pepper)
            wrongPepper <- security.hashPassword(
              Secret.fromString("correct horse"),
              entry.salt,
              Secret.Bytes16(Array.fill(16)(0.toByte)),
            )
          yield assertTrue(
            recomputed === entry.hash,
            // Pins that the pepper is threaded through rather than dropped -- a seeder that
            // ignored it would produce hashes that are individually well-formed and that auth,
            // which does not ignore it, rejects.
            !(wrongPepper === entry.hash),
          )
    },
    test("an empty batch does no work") {
      ZIO.scoped:
        fixture(parallelism = 4).flatMap: (hasher, _, peak) =>
          for
            hashed <- hasher.hashAll(Chunk.empty)
            observed <- peak.get
          yield assertTrue(hashed.isEmpty, observed == 0)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(3.minutes)
