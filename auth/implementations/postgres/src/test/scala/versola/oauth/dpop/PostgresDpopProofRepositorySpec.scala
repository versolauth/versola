package versola.oauth.dpop

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{Scope, ZIO, ZLayer, durationInt}

object PostgresDpopProofRepositorySpec extends PostgresSpec, DpopProofRepositorySpec:

  private val leeway = 60.seconds

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        repository = PostgresDpopProofRepository(xa)
      yield DpopProofRepositorySpec.Env(repository, repository.evictStaleSlot)

  override def beforeEach(env: DpopProofRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE dpop_proofs".update.run())
    yield ()

  override def testCases(
      env: DpopProofRepositorySpec.Env,
  ): List[Spec[DpopProofRepositorySpec.Env & Scope, Any]] =
    super.testCases(env) ++ List(
      test("reclaims the slot holding a proof once that proof has left the iat window") {
        // A slot becomes reclaimable a leeway plus one slot width after the proofs it holds
        // were created -- by then none of them would pass the `iat` check anyway.
        for
          recorded <- env.repository.recordIfAbsent("jkt-1", "jti-1", iat)
          _ <- env.evictStale(iat.plusSeconds(90), leeway)
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
    )
