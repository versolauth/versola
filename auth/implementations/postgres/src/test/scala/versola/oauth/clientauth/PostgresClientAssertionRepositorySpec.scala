package versola.oauth.clientauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{ZIO, ZLayer}

object PostgresClientAssertionRepositorySpec extends PostgresSpec, ClientAssertionRepositorySpec:

  import PostgresClientAssertionRepository.{MaxClockSkew, MaxLifetime, SlotWidth}

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresClientAssertionRepository(xa)
        ClientAssertionRepositorySpec.Env(
          repository = repository,
          evictStale = repository.evictStaleSlot,
          // A slot becomes reclaimable a slot width and the tolerated clock skew after the last
          // `exp` it could hold -- by then no instance would pass any of them through the
          // expiry check.
          reclaimableAfter = SlotWidth.plus(MaxClockSkew.multipliedBy(2)),
          maxClockSkew = MaxClockSkew,
        )

  override def beforeEach(env: ClientAssertionRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE client_assertions".update.run())
    yield ()

  override def implementationCases(env: ClientAssertionRepositorySpec.Env) =
    List(
      // The ring laps, so a slot is reused by assertions a lap apart. An assertion whose
      // lifetime reached past a lap would land in a slot still guarding a live record and be
      // reported as a replay -- which is why central's ceiling has to stay under this one.
      test("holds every lifetime central lets a tenant configure") {
        assertTrue(
          PostgresClientAssertionRepository.ConfigurableMaxLifetime.compareTo(MaxLifetime) <= 0,
        )
      },
      // `UNLOGGED` is a property of each partition and is not inherited from the parent, so a
      // partition added without the keyword is logged again -- with nothing to show for it but
      // WAL for rows engineered to expire within minutes.
      test("holds every partition of the ring unlogged") {
        for
          xa <- ZIO.service[TransactorZIO]
          logged <- xa.connect:
            sql"""
              SELECT c.relname
              FROM pg_class c
              JOIN pg_inherits i ON i.inhrelid = c.oid
              JOIN pg_class p ON p.oid = i.inhparent
              WHERE p.relname = 'client_assertions' AND c.relpersistence <> 'u'
            """.query[String].run()
        yield assertTrue(logged.isEmpty)
      },
    )
