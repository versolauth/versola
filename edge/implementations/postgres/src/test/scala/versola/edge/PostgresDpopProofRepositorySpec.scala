package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.DatabaseSpecBase
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

/** The fleet-wide half of edge's replay protection. Deliberately covers the same ground as
  * auth's repository spec: the two rings are separate code, and the only thing keeping them
  * from drifting apart in behaviour is that both are held to it.
  */
case class DpopProofEnv(repository: PostgresDpopProofRepository, xa: TransactorZIO)

object PostgresDpopProofRepositorySpec extends PostgresSpec, DatabaseSpecBase[DpopProofEnv]:

  val iat: Instant = Instant.parse("2024-01-01T00:00:00Z")
  val leeway: Duration = 60.seconds

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        repository = PostgresDpopProofRepository(xa)
      yield DpopProofEnv(repository, xa)

  override def beforeEach(env: DpopProofEnv) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect(sql"TRUNCATE TABLE edge_dpop_proofs".update.run())
    .unit

  def testCases(env: DpopProofEnv): List[Spec[DpopProofEnv & Scope, Any]] =
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
          _ <- env.repository.evictStaleSlot(iat.plusSeconds(150), leeway)
          afterEviction <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(recorded, afterEviction)
      },
      test("never reclaims a record while its proof could still be presented") {
        // The dangerous direction: dropping this record early would make the proof replayable.
        for
          recorded <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          _ <- env.repository.evictStaleSlot(iat.plusSeconds(59), leeway)
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
          _ <- env.repository.evictStaleSlot(iat.plusSeconds(149), leeway)
          replay <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
        yield assertTrue(recorded, !replay)
      },
      // `UNLOGGED` is a property of each partition and is not inherited from the parent, so a
      // partition added without the keyword is logged again -- with nothing to show for it but
      // WAL for rows that expire inside the minute. Nothing else fails when that happens,
      // which is why it is asserted rather than left to review.
      test("holds every partition of the ring unlogged") {
        for
          logged <- env.xa.connect:
            sql"""
              SELECT c.relname
              FROM pg_class c
              JOIN pg_inherits i ON i.inhrelid = c.oid
              JOIN pg_class p ON p.oid = i.inhparent
              WHERE p.relname = 'edge_dpop_proofs' AND c.relpersistence <> 'u'
            """.query[String].run()
        yield assertTrue(logged.isEmpty)
      },
      // A lock on the parent is held by Postgres on every partition too, so this stands in
      // for anything that could hold the one partition being truncated -- a stuck backend, a
      // manual VACUUM FULL. The slot number is computed from `now` before the wait, so a
      // truncate left blocked long enough would act on a decision that's gone stale by the
      // time it finally runs; `lock_timeout` is what keeps that window bounded instead of open
      // for as long as whatever's holding the lock takes.
      test("gives up waiting on a held lock instead of blocking indefinitely, and recovers once it clears") {
        val lockAcquired = java.util.concurrent.CountDownLatch(1)
        val releaseLock = java.util.concurrent.CountDownLatch(1)
        for
          locker <- env.xa.transact:
            sql"LOCK TABLE edge_dpop_proofs IN ACCESS EXCLUSIVE MODE".update.run()
            lockAcquired.countDown()
            releaseLock.await(10, java.util.concurrent.TimeUnit.SECONDS)
          .fork
          _ <- ZIO.attemptBlocking(lockAcquired.await(5, java.util.concurrent.TimeUnit.SECONDS))
          start <- Clock.instant
          blocked <- env.repository.evictStaleSlot(iat, leeway).either
          elapsed <- Clock.instant.map(now => java.time.Duration.between(start, now))
          _ <- ZIO.succeed(releaseLock.countDown())
          _ <- locker.join
          recovered <- env.repository.evictStaleSlot(iat, leeway).either
        yield assertTrue(
          blocked.isLeft,
          // Well under the 10s the lock holder would otherwise sit for -- proof this failed on
          // its own timeout rather than waiting the lock out.
          elapsed.toMillis < 5000L,
          recovered.isRight,
        )
      },
    )
