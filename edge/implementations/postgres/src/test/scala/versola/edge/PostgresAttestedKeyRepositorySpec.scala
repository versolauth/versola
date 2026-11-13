package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.attestation.IosEnvironment
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

/** The table behind App Attest: that a key is found as it was recorded, and that its counter only ever moves forward -- also when
  * two replicas are handed the same assertion at once, which is the whole point of keeping it in the database. */
object PostgresAttestedKeyRepositorySpec extends PostgresSpec:

  private val t0 = Instant.parse("2026-03-01T00:00:00Z")
  private val key = Array.fill[Byte](91)(5)

  private val setup =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE attested_keys".update.run())
    yield PostgresAttestedKeyRepository(xa)

  def spec = suite("PostgresAttestedKeyRepository")(
    test("a key that was never recorded is not found") {
      for
        repository <- setup
        found <- repository.find("mobile", "key-1")
      yield assertTrue(found.isEmpty)
    },
    test("a recorded key is found with its public key, and a counter of zero") {
      for
        repository <- setup
        _ <- repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
        found <- repository.find("mobile", "key-1")
      yield assertTrue(
        found.exists(_.publicKey.sameElements(key)),
        found.exists(_.environment == IosEnvironment.production),
        found.exists(_.counter == 0L),
      )
    },
    test("the same key id of another client is another key") {
      for
        repository <- setup
        _ <- repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
        other <- repository.find("another", "key-1")
      yield assertTrue(other.isEmpty)
    },
    test("recording a key again keeps its counter, and does not replace its public key") {
      for
        repository <- setup
        _ <- repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
        _ <- repository.advance("mobile", "key-1", 5, t0)
        _ <- repository.register("mobile", "key-1", Array.fill[Byte](91)(9), IosEnvironment.development, t0.plusSeconds(60))
        found <- repository.find("mobile", "key-1")
      yield assertTrue(
        found.exists(_.counter == 5L),
        found.exists(_.publicKey.sameElements(key)),
        found.exists(_.environment == IosEnvironment.production),
      )
    },
    test("the counter moves forward only: a value that is not greater is refused and changes nothing") {
      for
        repository <- setup
        _ <- repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
        first <- repository.advance("mobile", "key-1", 3, t0)
        same <- repository.advance("mobile", "key-1", 3, t0)
        behind <- repository.advance("mobile", "key-1", 2, t0)
        ahead <- repository.advance("mobile", "key-1", 4, t0)
        found <- repository.find("mobile", "key-1")
      yield assertTrue(first, !same, !behind, ahead, found.exists(_.counter == 4L))
    },
    test("an assertion for a key that is not recorded advances nothing") {
      for
        repository <- setup
        moved <- repository.advance("mobile", "unknown", 1, t0)
      yield assertTrue(!moved)
    },
    test("of replicas handed the same assertion at once, exactly one is let through") {
      for
        repository <- setup
        _ <- repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
        results <- ZIO.foreachPar(1 to 8)(_ => repository.advance("mobile", "key-1", 7, t0))
      yield assertTrue(results.count(identity) == 1)
    },
    test("a counter above what an int holds is kept as it is") {
      for
        repository <- setup
        _ <- repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
        _ <- repository.advance("mobile", "key-1", 0xffffffffL, t0)
        found <- repository.find("mobile", "key-1")
      yield assertTrue(found.exists(_.counter == 0xffffffffL))
    },
  ) @@ TestAspect.sequential
