package versola.central.configuration.challenges

import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

object SubmissionLimitsSpec extends UnitSpecBase:

  private val stepUp = StepUpLimits(
    otpRequest = List(RateLimit(1, 60)),
    otpSubmit = List(RateLimit(2, 120)),
    passwordSubmit = List(RateLimit(3, 900)),
    passkeyAssertion = List(RateLimit(3, 300)),
    banDurationSeconds = 3600,
  )

  val spec = suite("SubmissionLimits")(
    suite("JSON")(
      test("a payload without a step-up section decodes, step-up absent") {
        val json = """{"otpRequest":[],"otpSubmit":[],"passwordSubmit":[],"passkeyAssertion":[],"banDurationSeconds":0}"""
        assertTrue(json.fromJson[SubmissionLimits] == Right(SubmissionLimits.empty))
      },
      test("a step-up section round-trips") {
        val limits = SubmissionLimits.recommended.copy(stepUp = Some(stepUp))
        assertTrue(limits.toJson.fromJson[SubmissionLimits] == Right(limits))
      },
      test("an absent step-up section is not written out") {
        assertTrue(!SubmissionLimits.recommended.toJson.contains("stepUp"))
      },
      test("a step-up section missing a category is rejected rather than read as unthrottled") {
        val json =
          """{"otpRequest":[],"otpSubmit":[],"passwordSubmit":[],"passkeyAssertion":[],"banDurationSeconds":0,"stepUp":{"otpRequest":[]}}"""
        assertTrue(json.fromJson[SubmissionLimits].isLeft)
      },
    ),
    suite("isConfigured")(
      test("accepts the recommended limits, with or without a valid step-up section") {
        assertTrue(
          SubmissionLimits.isConfigured(SubmissionLimits.recommended),
          SubmissionLimits.isConfigured(SubmissionLimits.recommended.copy(stepUp = Some(stepUp))),
        )
      },
      test("rejects a step-up section with an empty category") {
        assertTrue(!SubmissionLimits.isConfigured(SubmissionLimits.recommended.copy(stepUp = Some(stepUp.copy(otpSubmit = Nil)))))
      },
      test("rejects a step-up section without a ban duration") {
        assertTrue(!SubmissionLimits.isConfigured(SubmissionLimits.recommended.copy(stepUp = Some(stepUp.copy(banDurationSeconds = 0)))))
      },
      test("rejects a step-up window with a non-positive size") {
        assertTrue(
          !SubmissionLimits.isConfigured(
            SubmissionLimits.recommended.copy(stepUp = Some(stepUp.copy(passwordSubmit = List(RateLimit(0, 900))))),
          ),
        )
      },
    ),
  )
