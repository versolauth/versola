package versola.oauth.client.model

import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object PasskeyPolicySpec extends UnitSpecBase:
  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val first = Instant.parse("2026-01-01T00:00:00Z")

  private def state(skips: Int) = Some(PasskeyEnrollmentState(userId = userId, firstOfferedAt = first, skips = skips))

  private def policy(mode: PasskeyEnrollmentMode, skips: Option[Int], days: Option[Int]) =
    PasskeyPolicy(mode = mode, graceSkips = skips, graceDays = days)

  private val grace = policy(PasskeyEnrollmentMode.requiredAfterGrace, Some(3), Some(14))

  def spec = suite("PasskeyPolicy")(
    suite("offer")(
      test("off offers nothing") {
        assertTrue(policy(PasskeyEnrollmentMode.off, None, None).offer(state(99), first) == PasskeyOffer.None)
      },
      test("offered is always skippable, however many offers were skipped") {
        assertTrue(policy(PasskeyEnrollmentMode.offered, None, None).offer(state(99), first.plusSeconds(10_000_000)) == PasskeyOffer.Skippable)
      },
      test("required is never skippable") {
        assertTrue(policy(PasskeyEnrollmentMode.required, None, None).offer(None, first) == PasskeyOffer.Mandatory)
      },
      test("required_after_grace is skippable for a user never offered before") {
        assertTrue(grace.offer(None, first) == PasskeyOffer.Skippable)
      },
      test("required_after_grace is skippable while both limits are open") {
        assertTrue(grace.offer(state(2), first.plusSeconds(13 * 86400L)) == PasskeyOffer.Skippable)
      },
      test("required_after_grace becomes mandatory at the skip limit") {
        assertTrue(grace.offer(state(3), first.plusSeconds(86400L)) == PasskeyOffer.Mandatory)
      },
      test("required_after_grace becomes mandatory when the days end, at the instant they end") {
        assertTrue(
          grace.offer(state(0), first.plusSeconds(14 * 86400L - 1)) == PasskeyOffer.Skippable,
          grace.offer(state(0), first.plusSeconds(14 * 86400L)) == PasskeyOffer.Mandatory,
        )
      },
      test("required_after_grace by skips only ignores time") {
        assertTrue(policy(PasskeyEnrollmentMode.requiredAfterGrace, Some(3), None).offer(state(2), first.plusSeconds(1_000_000_000L)) == PasskeyOffer.Skippable)
      },
      test("required_after_grace by days only ignores the skip count") {
        assertTrue(policy(PasskeyEnrollmentMode.requiredAfterGrace, None, Some(7)).offer(state(50), first.plusSeconds(86400L)) == PasskeyOffer.Skippable)
      },
      test("required_after_grace without any limit fails closed") {
        assertTrue(policy(PasskeyEnrollmentMode.requiredAfterGrace, None, None).offer(None, first) == PasskeyOffer.Mandatory)
      },
    ),
    suite("json")(
      test("read central's wire names") {
        val json = """{"mode":"required_after_grace","graceSkips":3,"graceDays":null}"""
        assertTrue(json.fromJson[PasskeyPolicy] == Right(policy(PasskeyEnrollmentMode.requiredAfterGrace, Some(3), None)))
      },
      test("refuse a mode that does not exist") {
        assertTrue("""{"mode":"sometimes","graceSkips":null,"graceDays":null}""".fromJson[PasskeyPolicy].isLeft)
      },
    ),
  )
