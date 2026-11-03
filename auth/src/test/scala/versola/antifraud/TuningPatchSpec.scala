package versola.antifraud

import versola.util.{Patch, UnitSpecBase}
import zio.*
import zio.json.*
import zio.test.*

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

object TuningPatchSpec extends UnitSpecBase:

  /** Every setting away from its default, so that a change to one is told from a reset to another. */
  private val current = Tuning(
    mode = TuningMode.enforce,
    challengeAt = 20,
    blockAt = 60,
    pow = PowDifficulty(minDifficulty = 10, maxDifficulty = 20, scoreStep = 5),
    thresholds = VelocityRules.Thresholds(
      phone = VelocityRules.Limit(perMinute = 1, perTenMinutes = 2, perHour = 3, perDay = 4, weight = 5),
      ip = VelocityRules.Limit(perMinute = 11, perTenMinutes = 12, perHour = 13, perDay = 14, weight = 15),
      device = VelocityRules.Limit(perMinute = 21, perTenMinutes = 22, perHour = 23, perDay = 24, weight = 25),
      asn = VelocityRules.Limit(perMinute = 31, perTenMinutes = 32, perHour = 33, perDay = 34, weight = 35),
      prefix = VelocityRules.Limit(perMinute = 41, perTenMinutes = 42, perHour = 43, perDay = 44, weight = 45),
      trustedDiscount = 7,
    ),
    smsLimits = SmsLimits(
      default = SmsLimit(sentPerHour = 30, minVolume = 40, minConversion = 0.2),
      byPrefix = Map("49" -> SmsLimit(25, 25, 0.3), "7" -> SmsLimit(40, 0, 0.1)),
    ),
    ruleWeights = Map("velocity_phone_1h" -> 40, "velocity_ip_1m" -> 5),
    shadowRules = Set("velocity_ip_1h", "velocity_ip_24h"),
  )

  private def read(text: String): Either[String, TuningPatch] = text.fromJson[TuningPatch]

  private def applied(text: String): Either[String, Tuning] = read(text).map(_.applyTo(current))

  /** The member names of a case class, in order. */
  inline private def members[T](using m: Mirror.ProductOf[T]): List[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(_.toString)

  def spec = suite("TuningPatch")(
    suite("reading")(
      test("an absent member is None, null is Deleted, a value is Modified, for a scalar, a group, a map and a set") {
        val patch = read(
          """{"mode":"shadow","blockAt":null,"pow":{"minDifficulty":null,"scoreStep":3},"thresholds":null,"ruleWeights":{"a":1,"b":null},"shadowRules":null}""",
        )
        assertTrue(
          patch == Right(
            TuningPatch.empty.copy(
              mode = Some(Patch.Modified(TuningMode.shadow)),
              blockAt = Some(Patch.Deleted),
              pow = Some(Patch.Modified(PowDifficultyPatch.empty.copy(minDifficulty = Some(Patch.Deleted), scoreStep = Some(Patch.Modified(3))))),
              thresholds = Some(Patch.Deleted),
              ruleWeights = Some(Patch.Modified(Map("a" -> Patch.Modified(1), "b" -> Patch.Deleted))),
              shadowRules = Some(Patch.Deleted),
            ),
          ),
          read("{}") == Right(TuningPatch.empty),
        )
      },
      test("a patch written out reads back as itself, null and map removals included") {
        val patch = TuningPatch.empty.copy(
          challengeAt = Some(Patch.Deleted),
          smsLimits = Some(
            Patch.Modified(
              SmsLimitsPatch.empty.copy(
                default = Some(Patch.Modified(SmsLimitPatch.empty.copy(minConversion = Some(Patch.Modified(0.5))))),
                byPrefix = Some(Patch.Modified(Map("49" -> Patch.Deleted, "44" -> Patch.Modified(SmsLimit(21, 0, 0.3))))),
              ),
            ),
          ),
          shadowRules = Some(Patch.Modified(Set("velocity_ip_1m"))),
        )
        assertTrue(
          patch.toJson.fromJson[TuningPatch] == Right(patch),
          patch.toJson.contains("\"49\":null"),
          patch.toJson.contains("\"challengeAt\":null"),
        )
      },
      test("a member that no setting has is refused at every level, not dropped") {
        val refusals = List(
          """{"blokAt":10}""",
          """{"pow":{"minDificulty":3}}""",
          """{"thresholds":{"fone":{"weight":1}}}""",
          """{"thresholds":{"phone":{"perMinut":1}}}""",
          """{"smsLimits":{"defualt":{}}}""",
          """{"smsLimits":{"default":{"sentPerHur":30}}}""",
          """{"smsLimits":{"byPrefix":{"49":{"sentPerHur":30}}}}""",
          """{"version":3}""",
          """{"userId":"00000000-0000-0000-0000-0000000000f1"}""",
        ).map(read)
        assertTrue(refusals.forall(_.isLeft), refusals.forall(_.left.exists(_.contains("invalid extra field"))))
      },
      test("a value of the wrong type is refused") {
        assertTrue(
          read("""{"blockAt":"high"}""").isLeft,
          read("""{"mode":"loud"}""").isLeft,
          read("""{"shadowRules":"velocity_ip_1m"}""").isLeft,
          read("""{"ruleWeights":{"a":"x"}}""").isLeft,
          read("""{"ruleWeights":[1]}""").isLeft,
        )
      },
      test("the settings themselves refuse a member they do not have, a stored one apart from the retired keys") {
        assertTrue(
          """{"blokAt":10}""".fromJson[Tuning].isLeft,
          """{"pow":{"minDificulty":3}}""".fromJson[Tuning].isLeft,
          """{"thresholds":{"phone":{"perMinut":1}}}""".fromJson[Tuning].isLeft,
          """{"smsLimits":{"byPrefix":{"49":{"sentPerHur":30}}}}""".fromJson[Tuning].isLeft,
        )
      },
    ),
    suite("applying")(
      test("an empty patch changes nothing") {
        assertTrue(TuningPatch.empty.applyTo(current) == current, applied("{}") == Right(current))
      },
      test("a value sets a scalar and leaves the others") {
        assertTrue(
          applied("""{"blockAt":80,"mode":"shadow"}""") == Right(current.copy(blockAt = 80, mode = TuningMode.shadow)),
        )
      },
      test("an object is merged member by member, at every depth") {
        val patched = applied(
          """{"pow":{"maxDifficulty":22},"thresholds":{"ip":{"perHour":99},"trustedDiscount":9},"smsLimits":{"default":{"sentPerHour":50}}}""",
        )
        assertTrue(
          patched == Right(
            current.copy(
              pow = current.pow.copy(maxDifficulty = 22),
              thresholds = current.thresholds.copy(ip = current.thresholds.ip.copy(perHour = 99), trustedDiscount = 9),
              smsLimits = current.smsLimits.copy(default = current.smsLimits.default.copy(sentPerHour = 50)),
            ),
          ),
        )
      },
      test("null on a map entry removes that entry, a value replaces the entry whole, and the other entries stay") {
        val patched = applied(
          """{"smsLimits":{"byPrefix":{"49":null,"7":{"sentPerHour":0,"minVolume":0,"minConversion":0.1},"44":{"sentPerHour":21,"minVolume":0,"minConversion":0.3}}},"ruleWeights":{"velocity_ip_1m":null,"velocity_asn_1h":7}}""",
        )
        assertTrue(
          patched.map(_.smsLimits.byPrefix) == Right(Map("7" -> SmsLimit(0, 0, 0.1), "44" -> SmsLimit(21, 0, 0.3))),
          patched.map(_.ruleWeights) == Right(Map("velocity_phone_1h" -> 40, "velocity_asn_1h" -> 7)),
          // removing an entry that is not there is not an error
          applied("""{"ruleWeights":{"nothing":null}}""") == Right(current),
        )
      },
      test("a set is replaced as a whole") {
        assertTrue(
          applied("""{"shadowRules":["velocity_asn_24h"]}""").map(_.shadowRules) == Right(Set("velocity_asn_24h")),
          applied("""{"shadowRules":[]}""").map(_.shadowRules) == Right(Set.empty[String]),
        )
      },
      test("null on a scalar puts it back to its default in Tuning.off") {
        assertTrue(
          applied("""{"mode":null,"challengeAt":null,"blockAt":null}""") ==
            Right(current.copy(mode = Tuning.off.mode, challengeAt = Tuning.off.challengeAt, blockAt = Tuning.off.blockAt)),
          applied("""{"pow":{"minDifficulty":null,"maxDifficulty":null,"scoreStep":null}}""").map(_.pow) == Right(Tuning.off.pow),
          applied("""{"thresholds":{"trustedDiscount":null}}""").map(_.thresholds.trustedDiscount) == Right(Tuning.off.thresholds.trustedDiscount),
          applied("""{"smsLimits":{"default":{"minConversion":null}}}""").map(_.smsLimits.default.minConversion) ==
            Right(Tuning.off.smsLimits.default.minConversion),
        )
      },
      test("null on a limit puts back the default of that key, whose weight is its own: not the weight of a Limit() of no key") {
        val reset = applied("""{"thresholds":{"ip":{"weight":null,"perHour":null}}}""").map(_.thresholds.ip)
        assertTrue(
          reset == Right(current.thresholds.ip.copy(weight = Tuning.off.thresholds.ip.weight, perHour = Tuning.off.thresholds.ip.perHour)),
          Tuning.off.thresholds.ip.weight != VelocityRules.Limit().weight,
          applied("""{"thresholds":{"device":null}}""").map(_.thresholds.device) == Right(Tuning.off.thresholds.device),
        )
      },
      test("null on a group or a map puts the whole of it back to what is in Tuning.off") {
        assertTrue(
          applied("""{"pow":null,"thresholds":null,"smsLimits":null,"ruleWeights":null,"shadowRules":null}""") ==
            Right(current.copy(
              pow = Tuning.off.pow,
              thresholds = Tuning.off.thresholds,
              smsLimits = Tuning.off.smsLimits,
              ruleWeights = Map.empty,
              shadowRules = Set.empty,
            )),
          applied("""{"smsLimits":{"byPrefix":null}}""").map(_.smsLimits) == Right(current.smsLimits.copy(byPrefix = Map.empty)),
          applied("""{"smsLimits":{"default":null}}""").map(_.smsLimits) == Right(current.smsLimits.copy(default = Tuning.off.smsLimits.default)),
        )
      },
      test("a patch on everything off is the first row") {
        assertTrue(read("""{"blockAt":60}""").map(_.applyTo(Tuning.off)) == Right(Tuning.off.copy(blockAt = 60)))
      },
    ),
    suite("the mirror")(
      test(
        "TuningPatch has the members of Tuning, and each nested patch those of the class it changes: a setting added to one is added to the other",
      ) {
        assertTrue(
          members[TuningPatch] == members[Tuning],
          members[PowDifficultyPatch] == members[PowDifficulty],
          members[ThresholdsPatch] == members[VelocityRules.Thresholds],
          members[LimitPatch] == members[VelocityRules.Limit],
          members[SmsLimitsPatch] == members[SmsLimits],
          members[SmsLimitPatch] == members[SmsLimit],
        )
      },
    ),
  )
