package versola.central.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[AntifraudSettingsRepository]] promises: one row, empty until the first change; a put that replaces the row
  * whole and moves its version by one; and a put on a named version that writes only while the row is still at it, with 0
  * the version of a row that is not there. */
trait AntifraudSettingsRepositorySpec extends DatabaseSpecBase[AntifraudSettingsRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudSettingsRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("there is no row before the first change") {
        for row <- repository.find
        yield assertTrue(row.isEmpty)
      },
      test("the first put creates the row at version 1, and a put replaces it as a whole and moves the version by one") {
        for
          first <- repository.put(tuned, "alice", at, None)
          stored <- repository.find
          second <- repository.put(other, "bob", at.plusSeconds(60), None)
          replaced <- repository.find
        yield assertTrue(
          first.contains(1L),
          stored.contains(VersionedAntifraudSettings(tuned, 1)),
          second.contains(2L),
          replaced.contains(VersionedAntifraudSettings(other, 2)),
        )
      },
      test("a put on the version that was read writes, and one on a version the row has left writes nothing and moves nothing") {
        for
          _ <- repository.put(other, "bootstrap", at, None)
          first <- repository.put(tuned, "alice", at, Some(1))
          stale <- repository.put(other, "bob", at.plusSeconds(1), Some(1))
          ahead <- repository.put(other, "bob", at.plusSeconds(1), Some(7))
          row <- repository.find
          next <- repository.put(other, "bob", at.plusSeconds(2), Some(2))
        yield assertTrue(
          first.contains(2L),
          stale.isEmpty,
          ahead.isEmpty,
          row.contains(VersionedAntifraudSettings(tuned, 2)),
          next.contains(3L),
        )
      },
      test("with no row, version 0 is the version a first put may name, and any other is refused") {
        for
          wrong <- repository.put(tuned, "alice", at, Some(1))
          none <- repository.find
          first <- repository.put(tuned, "alice", at, Some(0))
          again <- repository.put(other, "bob", at, Some(0))
          row <- repository.find
        yield assertTrue(wrong.isEmpty, none.isEmpty, first.contains(1L), again.isEmpty, row.contains(VersionedAntifraudSettings(tuned, 1)))
      },
      test("two puts on the same version: one wins, the others are refused") {
        for
          _ <- repository.put(other, "bootstrap", at, None)
          results <- ZIO.foreachPar(List("alice", "bob", "carol", "dave"))(actor => repository.put(tuned, actor, at, Some(1)))
          row <- repository.find
        yield assertTrue(results.count(_.isDefined) == 1, results.count(_.isEmpty) == 3, row.map(_.version).contains(2L))
      },
    )

object AntifraudSettingsRepositorySpec:
  case class Env(repository: AntifraudSettingsRepository)

  val at: Instant = Instant.parse("2026-10-26T10:00:00Z")

  private val limit = VelocityLimitDto(perMinute = 0, perTenMinutes = 0, perHour = 3, perDay = 0, weight = 50)

  val tuned: AntifraudSettings = AntifraudSettings(
    mode = AntifraudMode.enforce,
    challengeAt = 0,
    blockAt = 100,
    pow = PowDifficultyDto(minDifficulty = 10, maxDifficulty = 20, scoreStep = 5),
    thresholds = VelocityThresholdsDto(limit, limit, limit, limit, limit, 40),
    smsLimits = SmsLimitsDto(SmsLimitDto(10, 0, 0.1), Map("49" -> SmsLimitDto(2, 5, 0.2))),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )

  val other: AntifraudSettings = tuned.copy(mode = AntifraudMode.shadow, blockAt = 80, ruleWeights = Map.empty, shadowRules = Set.empty)
