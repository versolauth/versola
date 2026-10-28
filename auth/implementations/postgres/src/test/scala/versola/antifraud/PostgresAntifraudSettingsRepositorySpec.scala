package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.nio.file.{Files, Paths}
import java.time.Instant

object PostgresAntifraudSettingsRepositorySpec extends PostgresSpec:

  private val at = Instant.parse("2026-10-26T10:00:00Z")

  private val tuned = Tuning.off.copy(
    blockAt = 100,
    pow = PowDifficulty(minDifficulty = 10, maxDifficulty = 20, scoreStep = 5),
    smsLimits = SmsLimits(SmsLimit(10, 0, 0.1), Map("49" -> SmsLimit(2, 5, 0.2))),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )

  /** The statement of the migration that seeds the row, as written there. */
  private val seed: String =
    val file = "auth/implementations/postgres/migrations/V0024__antifraud_settings.sql"
    val path = List(file, s"../../../$file", "migrations/V0024__antifraud_settings.sql").map(Paths.get(_)).find(Files.exists(_)).get
    "(?s)INSERT INTO af_settings \\(.*?\\);".r.findFirstIn(Files.readString(path)).get

  /** The seeded row, back where the migration left it, and the version where it was. */
  private val clean: ZIO[TransactorZIO, Throwable, PostgresAntifraudSettingsRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_settings".update.run())
      _ <- xa.connect(summon[DbCon].connection.createStatement().execute(seed)).unit
    yield PostgresAntifraudSettingsRepository(xa)

  def spec = suite("PostgresAntifraudSettingsRepository")(
    test("the row the migration seeds is Tuning.off") {
      for
        repository <- clean
        row <- repository.find
      yield assertTrue(row.contains(Tuning.off))
    },
    test("a put replaces the row as a whole and moves the version") {
      for
        repository <- clean
        v0 <- repository.version
        _ <- repository.put(tuned, "alice", at)
        stored <- repository.find
        _ <- repository.put(Tuning.off, "bob", at.plusSeconds(60))
        reset <- repository.find
        v2 <- repository.version
      yield assertTrue(stored.contains(tuned), reset.contains(Tuning.off), v2 == v0 + 2)
    },
  ) @@ TestAspect.sequential
