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
    test("seeding writes the first row once and never over a row an operator saved") {
      for
        repository <- clean
        first <- repository.seed(Tuning.off, "bootstrap", at)
        second <- repository.seed(Tuning.off, "bootstrap", at)
        _ <- repository.put(tuned, "alice", at)
        third <- repository.seed(Tuning.off, "bootstrap", at)
        row <- repository.find
        version <- repository.version
      yield assertTrue(first, !second, !third, row.contains(tuned), version == 2L)
    },
    test("a value that is not a Tuning fails the read the way the codec reports it, and the row is left as it is") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"challengeAt": "many"}'::jsonb, 'hand', $at)""".update.run())
        failure <- repository.find.flip
        raw <- xa.connect(sql"SELECT settings::text FROM af_settings WHERE id = 1".query[String].run().head)
      yield assertTrue(failure.isInstanceOf[SqlException], failure.getMessage.contains("challengeAt(expected an Int)"), raw.contains("many"))
    },
    test("a put replaces the row as a whole and moves the version") {
      for
        repository <- clean
        _ <- repository.seed(Tuning.off, "bootstrap", at)
        v0 <- repository.version
        _ <- repository.put(tuned, "alice", at)
        stored <- repository.find
        _ <- repository.put(Tuning.off, "bob", at.plusSeconds(60))
        reset <- repository.find
        v2 <- repository.version
      yield assertTrue(stored.contains(tuned), reset.contains(Tuning.off), v2 == v0 + 2)
    },
  ) @@ TestAspect.sequential
