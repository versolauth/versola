package versola.oauth.client.model

import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

object SubmissionLimitsSpec extends UnitSpecBase:

  private val signIn = SubmissionLimits(
    otpRequest = List(RateLimit(2, 60)),
    otpSubmit = List(RateLimit(3, 120)),
    passwordSubmit = List(RateLimit(5, 900)),
    passkeyAssertion = List(RateLimit(5, 300)),
    banDurationSeconds = 1800,
  )

  private val stepUpLimits = StepUpLimits(
    otpRequest = List(RateLimit(1, 60)),
    otpSubmit = List(RateLimit(2, 120)),
    passwordSubmit = List(RateLimit(3, 900)),
    passkeyAssertion = List(RateLimit(3, 300)),
    banDurationSeconds = 3600,
  )

  val spec = suite("SubmissionLimits")(
    test("decodes the payload of a tenant that predates step-up limits, with no step-up section") {
      val json = """{"otpRequest":[{"maxAttempts":2,"windowSeconds":60}],"otpSubmit":[],"passwordSubmit":[],"passkeyAssertion":[],"banDurationSeconds":1800}"""
      assertTrue(json.fromJson[SubmissionLimits].map(_.stepUp) == Right(None))
    },
    test("round-trips a configured step-up section") {
      val limits = signIn.copy(stepUp = Some(stepUpLimits))
      assertTrue(limits.toJson.fromJson[SubmissionLimits] == Right(limits))
    },
    test("forStepUp is the sign-in limits when no step-up section is configured") {
      assertTrue(signIn.forStepUp == signIn)
    },
    test("forStepUp replaces every window and the ban duration with the step-up ones") {
      val forStepUp = signIn.copy(stepUp = Some(stepUpLimits)).forStepUp
      assertTrue(
        forStepUp.otpRequest == stepUpLimits.otpRequest,
        forStepUp.otpSubmit == stepUpLimits.otpSubmit,
        forStepUp.passwordSubmit == stepUpLimits.passwordSubmit,
        forStepUp.passkeyAssertion == stepUpLimits.passkeyAssertion,
        forStepUp.banDurationSeconds == 3600,
      )
    },
    test("an empty step-up category stays empty rather than falling back to sign-in") {
      val forStepUp = signIn.copy(stepUp = Some(stepUpLimits.copy(passwordSubmit = Nil))).forStepUp
      assertTrue(forStepUp.passwordSubmit.isEmpty, forStepUp.otpSubmit == stepUpLimits.otpSubmit)
    },
  )
