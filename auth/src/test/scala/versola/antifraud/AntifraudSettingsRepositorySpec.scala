package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[AntifraudSettingsRepository]] promises: one row, empty until the first copy; a replacement that writes central's
  * tuning at central's version, replaces the row whole and moves the reload signal, or removes the row where central has none; and
  * a row another build wrote reads by the rules of the codec (a key added since has its default, a retired key is passed over, a
  * key nothing knows or a value of the wrong type fails the read). Such a row is stored through
  * [[AntifraudSettingsRepositorySpec.Fixtures]]. */
trait AntifraudSettingsRepositorySpec extends DatabaseSpecBase[AntifraudSettingsRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudSettingsRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("the table is empty before the first change, with version 0") {
        for
          row <- repository.find
          version <- repository.version
        yield assertTrue(row.isEmpty, version == 0L)
      },
      test("replace(Some) writes the tuning at central's version and moves the reload signal") {
        for
          v0 <- repository.version
          _ <- repository.replace(Some(tuned), 7, at)
          stored <- repository.find
          v1 <- repository.version
        yield assertTrue(stored.contains(StoredTuning(tuned, 7)), v1 == v0 + 1)
      },
      test("replace(Some) replaces the row as a whole and takes the new version, whichever way it moved") {
        for
          _ <- repository.replace(Some(tuned), 7, at)
          _ <- repository.replace(Some(Tuning.off), 3, at.plusSeconds(60))
          lower <- repository.find
          _ <- repository.replace(Some(tuned), 9, at.plusSeconds(120))
          higher <- repository.find
          v <- repository.version
        yield assertTrue(lower.contains(StoredTuning(Tuning.off, 3)), higher.contains(StoredTuning(tuned, 9)), v == 3L)
      },
      test("replace(None) removes the row, which is everything off, and moves the reload signal") {
        for
          _ <- repository.replace(Some(tuned), 7, at)
          before <- repository.version
          _ <- repository.replace(None, 0, at.plusSeconds(1))
          row <- repository.find
          after <- repository.version
        yield assertTrue(row.isEmpty, after == before + 1)
      },
      test("replace(None) with no row there is no failure") {
        for
          _ <- repository.replace(None, 0, at)
          row <- repository.find
        yield assertTrue(row.isEmpty)
      },
      test("a value that is not a Tuning fails the read the way the codec reports it, and the row is left as it is") {
        for
          _ <- env.fixtures.store(settings = """{"challengeAt": "many"}""", updatedBy = "hand", updatedAt = at)
          failure <- repository.find.flip
          raw <- env.fixtures.stored
        yield assertTrue(env.fixtures.isReadFailure(failure), failure.getMessage.contains("challengeAt(expected an Int)"), raw.contains("many"))
      },
      test("a row written before a key existed reads with the key's default: shadow for the mode, off for the rest") {
        for
          _ <- env.fixtures.store(settings = """{"blockAt": 80, "shadowRules": ["velocity_ip_1m"]}""", updatedBy = "old", updatedAt = at)
          stored <- repository.find
        yield assertTrue(
          stored.map(_.tuning).contains(Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))),
          stored.exists(_.tuning.mode == TuningMode.shadow),
        )
      },
      test("a row that names a key retired by an earlier build still reads; one that names a key nothing knows fails the read") {
        for
          _ <- env.fixtures.store(settings = """{"blockAt": 80, "delayAt": 3, "delayMillis": 2000}""", updatedBy = "old", updatedAt = at)
          retired <- repository.find
          _ <- env.fixtures.replace(settings = """{"blokAt": 80}""")
          unknown <- repository.find.flip
        yield assertTrue(
          retired.map(_.tuning).contains(Tuning.off.copy(blockAt = 80)),
          env.fixtures.isReadFailure(unknown),
          unknown.getMessage.contains("invalid extra field"),
        )
      },
    )

object AntifraudSettingsRepositorySpec:
  case class Env(repository: AntifraudSettingsRepository, fixtures: Fixtures)

  /** The row as it is stored, which the repository only writes from a [[Tuning]]: what another build, or a hand, left there. The
    * storage's runner supplies it. */
  trait Fixtures:
    /** Writes the one row with `settings` as its JSON, where there is none. */
    def store(settings: String, updatedBy: String, updatedAt: Instant): Task[Unit]

    /** Puts `settings` in place of the JSON of the row that is there. */
    def replace(settings: String): Task[Unit]

    /** The JSON of the row, as text. */
    def stored: Task[String]

    /** Whether `failure` is how this storage reports a value it could not read into the type asked for. */
    def isReadFailure(failure: Throwable): Boolean

  val at: Instant = Instant.parse("2026-10-26T10:00:00Z")

  val tuned: Tuning = Tuning.off.copy(
    mode = TuningMode.enforce,
    blockAt = 100,
    pow = PowDifficulty(minDifficulty = 10, maxDifficulty = 20, scoreStep = 5),
    smsLimits = SmsLimits(SmsLimit(10, 0, 0.1), Map("49" -> SmsLimit(2, 5, 0.2))),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )
