package versola.oauth.dpop

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{Scope, ZIO, ZLayer}

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
    super.testCases(env) :+
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
      }
