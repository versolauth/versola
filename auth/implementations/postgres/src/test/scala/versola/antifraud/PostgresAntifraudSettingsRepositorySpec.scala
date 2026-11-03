package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

object PostgresAntifraudSettingsRepositorySpec extends PostgresSpec:

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private val at = Instant.parse("2026-10-26T10:00:00Z")

  private val tuned = Tuning.off.copy(
    mode = TuningMode.enforce,
    blockAt = 100,
    pow = PowDifficulty(minDifficulty = 10, maxDifficulty = 20, scoreStep = 5),
    smsLimits = SmsLimits(SmsLimit(10, 0, 0.1), Map("49" -> SmsLimit(2, 5, 0.2))),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )

  /** Empty, as the migration leaves it; the version row is kept, since a change creates it. */
  private val clean: ZIO[TransactorZIO, Throwable, PostgresAntifraudSettingsRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_settings".update.run())
      _ <- xa.connect(sql"DELETE FROM af_settings_version".update.run())
    yield PostgresAntifraudSettingsRepository(xa)

  def spec = suite("PostgresAntifraudSettingsRepository")(
    test("the table is empty before the first change, with version 0") {
      for
        repository <- clean
        row <- repository.find
        version <- repository.version
      yield assertTrue(row.isEmpty, version == 0L)
    },
    test("seeding writes the first row once, at version 1, and never over a row an operator saved") {
      for
        repository <- clean
        first <- repository.seed(Tuning.off, "bootstrap", at)
        second <- repository.seed(Tuning.off, "bootstrap", at)
        seeded <- repository.find
        saved <- repository.put(tuned, "alice", at, None)
        third <- repository.seed(Tuning.off, "bootstrap", at)
        row <- repository.find
        version <- repository.version
      yield assertTrue(
        first,
        !second,
        !third,
        seeded.contains(StoredTuning(Tuning.off, 1)),
        saved.contains(2L),
        row.contains(StoredTuning(tuned, 2)),
        version == 2L,
      )
    },
    test("a value that is not a Tuning fails the read the way the codec reports it, and the row is left as it is") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(
          sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"challengeAt": "many"}'::jsonb, 'hand', $at)""".update.run(),
        )
        failure <- repository.find.flip
        raw <- xa.connect(sql"SELECT settings::text FROM af_settings WHERE id = 1".query[String].run().head)
      yield assertTrue(failure.isInstanceOf[SqlException], failure.getMessage.contains("challengeAt(expected an Int)"), raw.contains("many"))
    },
    test("a row written before a key existed reads with the key's default: shadow for the mode, off for the rest") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(
          sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"blockAt": 80, "shadowRules": ["velocity_ip_1m"]}'::jsonb, 'old', $at)""".update.run(),
        )
        stored <- repository.find
      yield assertTrue(
        stored.map(_.tuning).contains(Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))),
        stored.exists(_.tuning.mode == TuningMode.shadow),
      )
    },
    test("a row that names a key retired by an earlier build still reads; one that names a key nothing knows fails the read") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(
          sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"blockAt": 80, "delayAt": 3, "delayMillis": 2000}'::jsonb, 'old', $at)""".update.run(),
        )
        retired <- repository.find
        _ <- xa.connect(sql"""UPDATE af_settings SET settings = '{"blokAt": 80}'::jsonb WHERE id = 1""".update.run())
        unknown <- repository.find.flip
      yield assertTrue(
        retired.map(_.tuning).contains(Tuning.off.copy(blockAt = 80)),
        unknown.isInstanceOf[SqlException],
        unknown.getMessage.contains("invalid extra field"),
      )
    },
    test("a put replaces the row as a whole and moves both versions") {
      for
        repository <- clean
        _ <- repository.seed(Tuning.off, "bootstrap", at)
        v0 <- repository.version
        a <- repository.put(tuned, "alice", at, None)
        stored <- repository.find
        b <- repository.put(Tuning.off, "bob", at.plusSeconds(60), None)
        reset <- repository.find
        v2 <- repository.version
      yield assertTrue(
        a.contains(2L),
        b.contains(3L),
        stored.contains(StoredTuning(tuned, 2)),
        reset.contains(StoredTuning(Tuning.off, 3)),
        v2 == v0 + 2,
      )
    },
    test("a put on the version that was read writes, and one on a version the row has left writes nothing and moves nothing") {
      for
        repository <- clean
        _ <- repository.seed(Tuning.off, "bootstrap", at)
        first <- repository.put(tuned, "alice", at, Some(1))
        before <- repository.version
        stale <- repository.put(Tuning.off, "bob", at.plusSeconds(1), Some(1))
        ahead <- repository.put(Tuning.off, "bob", at.plusSeconds(1), Some(7))
        after <- repository.version
        row <- repository.find
        next <- repository.put(Tuning.off, "bob", at.plusSeconds(2), Some(2))
      yield assertTrue(
        first.contains(2L),
        stale.isEmpty,
        ahead.isEmpty,
        after == before,
        row.contains(StoredTuning(tuned, 2)),
        next.contains(3L),
      )
    },
    test("with no row, version 0 is the version a first put may name, and any other is refused") {
      for
        repository <- clean
        wrong <- repository.put(tuned, "alice", at, Some(1))
        none <- repository.find
        first <- repository.put(tuned, "alice", at, Some(0))
        again <- repository.put(Tuning.off, "bob", at, Some(0))
        row <- repository.find
      yield assertTrue(wrong.isEmpty, none.isEmpty, first.contains(1L), again.isEmpty, row.contains(StoredTuning(tuned, 1)))
    },
    test("two puts on the same version: one wins, the other is refused") {
      for
        repository <- clean
        _ <- repository.seed(Tuning.off, "bootstrap", at)
        results <- ZIO.foreachPar(List("alice", "bob", "carol", "dave"))(actor => repository.put(tuned, actor, at, Some(1)))
        row <- repository.find
      yield assertTrue(results.count(_.isDefined) == 1, results.count(_.isEmpty) == 3, row.map(_.version).contains(2L))
    },
    test("every method fails on its statement timeout instead of waiting for a lock") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.seed(Tuning.off, "bootstrap", at)
        impatient = PostgresAntifraudSettingsRepository(xa, statementTimeout = 200.millis)
        // A transaction that holds the table, as a long migration or a stuck writer would.
        holder <- xa.transact:
          sql"LOCK TABLE af_settings IN ACCESS EXCLUSIVE MODE".update.run()
          Thread.sleep(3000)
        .fork
        _ <- Live.live(ZIO.sleep(500.millis))
        find <- Live.live(impatient.find.exit)
        put <- Live.live(impatient.put(tuned, "alice", at, Some(1)).exit)
        seed <- Live.live(impatient.seed(Tuning.off, "bootstrap", at).exit)
        _ <- holder.join
        healthy <- impatient.find
      yield assertTrue(find.isFailure, put.isFailure, seed.isFailure, healthy.map(_.version).contains(1L))
    },
    test("the timeout covers the version read, which sees a table that is not locked") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        impatient = PostgresAntifraudSettingsRepository(xa, statementTimeout = 200.millis)
        holder <- xa.transact:
          sql"LOCK TABLE af_settings_version IN ACCESS EXCLUSIVE MODE".update.run()
          Thread.sleep(3000)
        .fork
        _ <- Live.live(ZIO.sleep(500.millis))
        version <- Live.live(impatient.version.exit)
        _ <- holder.join
      yield assertTrue(version.isFailure)
    },
  ) @@ TestAspect.sequential
