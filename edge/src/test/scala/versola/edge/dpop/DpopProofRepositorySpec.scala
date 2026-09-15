package versola.edge.dpop

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** The fleet-wide half of edge's replay protection. Deliberately covers the same ground as
  * auth's repository spec: the two rings are separate code, and the only thing keeping them
  * from drifting apart in behaviour is that both are held to it.
  */
trait DpopProofRepositorySpec extends DatabaseSpecBase[DpopProofRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val iat: Instant = Instant.parse("2024-01-01T00:00:00Z")
  val leeway: Duration = 60.seconds

  def testCases(env: DpopProofRepositorySpec.Env): List[Spec[DpopProofRepositorySpec.Env & Scope, Any]] =
    List(
      test("records a new (jkt, jti) pair and reports it as fresh") {
        for fresh <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(fresh)
      },
      test("reports a replay for the same (jkt, jti) pair recorded twice") {
        // The case the in-memory ring cannot answer: in production these two calls are two
        // pods, and only this record is shared between them.
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          second <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(first, !second)
      },
      test("treats the same jti under a different jkt as a distinct, fresh pair") {
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          second <- env.repository.recordIfAbsent("jkt-2", "jti-1", iat)
        yield assertTrue(first, second)
      },
      test("treats a different jti under the same jkt as a distinct, fresh pair") {
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          second <- env.repository.recordIfAbsent("jkt-1", "jti-2", iat)
        yield assertTrue(first, second)
      },
      test("detects a replay however much later in the window it arrives") {
        // The record is placed by the proof's own `iat`, not by the time it shows up, so a
        // captured proof cannot be held back and replayed into a record of its own.
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          _ <- TestClock.adjust(59.seconds)
          second <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(first, !second)
      },
      test("concurrent attempts to record the same pair -- only one should see it as fresh") {
        // Exactly one `true` is the whole contract. It holds today because the unique index
        // settles it inside the database; any future batching of this write has to reproduce
        // it in the batch itself, where two copies of one digest become one inserted row.
        for
          results <- ZIO.collectAllPar(
            List.fill(10)(env.repository.recordIfAbsent("jkt-1", "jti-1", iat)),
          )
        yield assertTrue(results.count(identity) == 1)
      },
      test("reclaims the slot holding a proof once that proof has left the iat window") {
        for
          recorded <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          _ <- env.evictStale(iat.plusSeconds(150), leeway)
          afterEviction <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(recorded, afterEviction)
      },
      test("never reclaims a record while its proof could still be presented") {
        // The dangerous direction: dropping this record early would make the proof replayable.
        for
          recorded <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          _ <- env.evictStale(iat.plusSeconds(59), leeway)
          replay <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(recorded, !replay)
      },
      test("never reclaims a record a pod behind the evicting one would still accept") {
        // Eviction reads the clock of whichever pod runs it, acceptance the clock of whichever
        // pod the proof reaches. A pod running ahead must not truncate a slot at the moment
        // its own window ends: a peer that far behind still accepts the `iat`s in it, and
        // would then have no record to reject the replay against. This is the margin the
        // in-memory ring does not need and this one does.
        for
          recorded <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          _ <- env.evictStale(iat.plusSeconds(149), leeway)
          replay <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(recorded, !replay)
      },
    )

object DpopProofRepositorySpec:
  /** @param evictStale reclaims the space held by records whose proofs can no longer be
    *   presented, given the `iat` leeway the server is running with. How much it reclaims per
    *   call is up to the implementation, so the cases that pin it down live alongside one.
    * @param xa for the cases an implementation adds about its own storage, which the shared
    *   behaviour here has no way to express.
    */
  case class Env(
      repository: DpopProofRepository,
      evictStale: (Instant, Duration) => Task[Unit],
      xa: TransactorZIO,
  )
