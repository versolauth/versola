package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[AntifraudSettingsRepository]] promises: one row, empty until the first change; a seed that writes once and
  * never over an operator's row; a put that replaces the row whole and moves both the row's version and the reload signal;
  * a put on a named version that writes only while the row is still at it, with 0 the version of a row that is not there; and
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
      test("seeding writes the first row once, at version 1, and never over a row an operator saved") {
        for
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
      test("a put replaces the row as a whole and moves both versions") {
        for
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
          wrong <- repository.put(tuned, "alice", at, Some(1))
          none <- repository.find
          first <- repository.put(tuned, "alice", at, Some(0))
          again <- repository.put(Tuning.off, "bob", at, Some(0))
          row <- repository.find
        yield assertTrue(wrong.isEmpty, none.isEmpty, first.contains(1L), again.isEmpty, row.contains(StoredTuning(tuned, 1)))
      },
      test("two puts on the same version: one wins, the other is refused") {
        for
          _ <- repository.seed(Tuning.off, "bootstrap", at)
          results <- ZIO.foreachPar(List("alice", "bob", "carol", "dave"))(actor => repository.put(tuned, actor, at, Some(1)))
          row <- repository.find
        yield assertTrue(results.count(_.isDefined) == 1, results.count(_.isEmpty) == 3, row.map(_.version).contains(2L))
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
