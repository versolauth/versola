package versola.oauth.clientauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.DatabaseSpecBase
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{Scope, ZIO, ZLayer}

import java.time.Instant

object PostgresClientAssertionRepositorySpec
    extends PostgresSpec,
      DatabaseSpecBase[PostgresClientAssertionRepositorySpec.Env]:

  import PostgresClientAssertionRepository.{MaxClockSkew, MaxLifetime, SlotWidth}

  case class Env(repository: PostgresClientAssertionRepository, xa: TransactorZIO)

  /** An assertion's `exp`, which is what the record is keyed by. */
  private val exp = Instant.parse("2024-01-01T00:05:00Z")

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresClientAssertionRepository(xa), xa)

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE client_assertions".update.run())
    yield ()

  override def testCases(env: Env): List[Spec[Env & Scope, Any]] =
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
        // A slot becomes reclaimable a slot width and the tolerated clock skew after the last
        // `exp` it could hold -- by then no instance would pass any of them through the
        // expiry check.
        for
          recorded <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          _ <- env.repository.evictStaleSlot(exp.plus(SlotWidth).plus(MaxClockSkew.multipliedBy(2)))
          afterEviction <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(recorded, afterEviction)
      },
      test("never reclaims a record while its assertion could still be presented") {
        // The dangerous direction: dropping this record before `exp` would make the assertion
        // replayable for the rest of its life.
        for
          recorded <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          _ <- env.repository.evictStaleSlot(exp.minusSeconds(1))
          replay <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(recorded, !replay)
      },
      test("never reclaims a record an instance behind the evicting one would still accept") {
        // Eviction reads the clock of whichever instance runs it, expiry the clock of
        // whichever instance the assertion reaches. An instance running ahead must not
        // truncate the slot the moment `exp` passes on its own clock: a peer that far behind
        // still accepts it, and would have no record to reject the replay against.
        for
          recorded <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
          _ <- env.repository.evictStaleSlot(exp.plus(MaxClockSkew))
          replay <- env.repository.recordIfAbsent("client-1", "jti-1", exp)
        yield assertTrue(recorded, !replay)
      },
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
          logged <- env.xa.connect:
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
