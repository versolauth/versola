package versola.oauth.clientauth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[ClientAssertionRepository]] promises: a `(client, jti)` pair is fresh once, whoever records it at the same time,
  * and a record is not given up while its assertion could still be presented to any instance. How a storage gives a record up is
  * its own, so the eviction and its margins come with [[ClientAssertionRepositorySpec.Env]]; the storage adds in
  * `implementationCases` what it alone promises. */
trait ClientAssertionRepositorySpec extends DatabaseSpecBase[ClientAssertionRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  /** An assertion's `exp`, which is what the record is keyed by. */
  val exp: Instant = Instant.parse("2024-01-01T00:05:00Z")

  def testCases(env: ClientAssertionRepositorySpec.Env): List[Spec[ClientAssertionRepositorySpec.Env & Scope, Any]] =
    List(
      test("records a new (client, jti) pair and reports it as fresh") {
        for fresh <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(fresh)
      },
      test("reports a replay for the same pair recorded twice") {
        for
          first <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          second <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(first, !second)
      },
      // RFC 7523 leaves `jti` uniqueness to the issuer, and two clients issue independently.
      // Scoping the record by client is what stops one client's choice of `jti` from refusing
      // another client's assertion.
      test("treats the same jti under a different client as a distinct, fresh pair") {
        for
          first <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          second <- env.repository.recordIfAbsent("client-2", "jti-1", exp)
        yield assertTrue(first, second)
      },
      test("concurrent attempts to record the same pair -- only one should see it as fresh") {
        for results <- ZIO.collectAllPar(List.fill(10)(env.repository.recordIfAbsent("client-1", "jti-1", exp)))
        yield assertTrue(results.count(identity) == 1)
      },
      test("reclaims the slot holding an assertion once that assertion has expired") {
        // A record becomes reclaimable once no instance would pass its assertion through the
        // expiry check, however the storage groups records and whichever clock runs the eviction.
        for
          recorded <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          _ <- env.evictStale(exp.plus(env.reclaimableAfter))
          afterEviction <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(recorded, afterEviction)
      },
      test("never reclaims a record while its assertion could still be presented") {
        // The dangerous direction: dropping this record before `exp` would make the assertion
        // replayable for the rest of its life.
        for
          recorded <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          _ <- env.evictStale(exp.minusSeconds(1))
          replay <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(recorded, !replay)
      },
      test("never reclaims a record an instance behind the evicting one would still accept") {
        // Eviction reads the clock of whichever instance runs it, expiry the clock of
        // whichever instance the assertion reaches. An instance running ahead must not
        // give the record up the moment `exp` passes on its own clock: a peer that far behind
        // still accepts it, and would have no record to reject the replay against.
        for
          recorded <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          _ <- env.evictStale(exp.plus(env.maxClockSkew))
          replay <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(recorded, !replay)
      },
    )

object ClientAssertionRepositorySpec:

  /** @param evictStale
    *   the storage's own reclaiming of the records no assertion needs any longer, run as if the instance's clock read the instant given
    * @param reclaimableAfter
    *   how long after an assertion's `exp` the storage may have given its record up, on any instance's clock
    * @param maxClockSkew
    *   how far apart the clocks of two instances are tolerated to be: a record is kept at least that long past its `exp`
    */
  case class Env(
      repository: ClientAssertionRepository,
      evictStale: Instant => Task[Unit],
      reclaimableAfter: Duration,
      maxClockSkew: Duration,
  )
