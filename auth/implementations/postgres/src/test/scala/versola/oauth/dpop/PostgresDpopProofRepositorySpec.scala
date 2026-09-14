package versola.oauth.dpop

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{Clock, Scope, ZIO, ZLayer}

object PostgresDpopProofRepositorySpec extends PostgresSpec, DpopProofRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        repository = PostgresDpopProofRepository(xa)
      yield DpopProofRepositorySpec.Env(repository, repository.evictStaleSlot, xa)

  override def beforeEach(env: DpopProofRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE dpop_proofs".update.run())
    yield ()

  override def testCases(
      env: DpopProofRepositorySpec.Env,
  ): List[Spec[DpopProofRepositorySpec.Env & Scope, Any]] =
    super.testCases(env) ++ List(
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
              WHERE p.relname = 'dpop_proofs' AND c.relpersistence <> 'u'
            """.query[String].run()
        yield assertTrue(logged.isEmpty)
      },
      // A lock on the parent is held by Postgres on every partition too, so this stands in
      // for anything that could hold the one partition being truncated -- a stuck instance, a
      // manual VACUUM FULL. The slot number is computed from `now` before the wait, so a
      // truncate left blocked long enough would act on a decision that's gone stale by the
      // time it finally runs; `lock_timeout` is what keeps that window bounded instead of open
      // for as long as whatever's holding the lock takes.
      test("gives up waiting on a held lock instead of blocking indefinitely, and recovers once it clears") {
        val lockAcquired = java.util.concurrent.CountDownLatch(1)
        val releaseLock = java.util.concurrent.CountDownLatch(1)
        for
          locker <- env.xa.transact:
            sql"LOCK TABLE dpop_proofs IN ACCESS EXCLUSIVE MODE".update.run()
            lockAcquired.countDown()
            releaseLock.await(10, java.util.concurrent.TimeUnit.SECONDS)
          .fork
          _ <- ZIO.attemptBlocking(lockAcquired.await(5, java.util.concurrent.TimeUnit.SECONDS))
          start <- Clock.instant
          blocked <- env.evictStale(iat, leeway).either
          elapsed <- Clock.instant.map(now => java.time.Duration.between(start, now))
          _ <- ZIO.succeed(releaseLock.countDown())
          _ <- locker.join
          recovered <- env.evictStale(iat, leeway).either
        yield assertTrue(
          blocked.isLeft,
          // Well under the 10s the lock holder would otherwise sit for -- proof this failed on
          // its own timeout rather than waiting the lock out.
          elapsed.toMillis < 5000L,
          recovered.isRight,
        )
      },
    )
