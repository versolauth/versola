package versola.oauth.client.model

import zio.json.JsonCodec

case class RateLimit(maxAttempts: Int, windowSeconds: Int) derives JsonCodec

/** Limits of a step-up (re-verifying an existing session toward a target ACR), configured apart
  * from the sign-in ones: same shape, evaluated against the step-up throttle records.
  */
case class StepUpLimits(
    otpRequest: List[RateLimit] = Nil,
    otpSubmit: List[RateLimit] = Nil,
    passwordSubmit: List[RateLimit] = Nil,
    passkeyAssertion: List[RateLimit] = Nil,
    banDurationSeconds: Int = 0,
) derives JsonCodec

case class SubmissionLimits(
    otpRequest: List[RateLimit] = Nil,
    otpSubmit: List[RateLimit] = Nil,
    passwordSubmit: List[RateLimit] = Nil,
    passkeyAssertion: List[RateLimit] = Nil,
    banDurationSeconds: Int = 0,
    /** `None` for a tenant that never configured step-up limits: its step-ups then follow the
      * sign-in windows above (with their own counters), so existing tenants keep their throttling.
      * `Some` replaces them entirely, including the ban duration -- an empty list there switches
      * that step-up challenge's throttling off, as it does for sign-in.
      */
    stepUp: Option[StepUpLimits] = None,
) derives JsonCodec:

  /** The limits that govern a step-up: the dedicated ones when configured, otherwise these. */
  def forStepUp: SubmissionLimits =
    stepUp.fold(this)(s => SubmissionLimits(s.otpRequest, s.otpSubmit, s.passwordSubmit, s.passkeyAssertion, s.banDurationSeconds))

object SubmissionLimits:
  val empty: SubmissionLimits = SubmissionLimits()

  /** Mirrors `SubmissionLimits.recommended` in `central`'s equivalent model -- keep the two
    * in sync. Used as the fallback here (rather than `empty`) when a tenant has no
    * `ChallengeSettingsRecord` at all, so an unconfigured tenant still gets throttled
    * instead of accepting unlimited password/OTP/passkey submission attempts.
    */
  val recommended: SubmissionLimits = SubmissionLimits(
    otpRequest = List(RateLimit(2, 60), RateLimit(5, 3600)),
    otpSubmit = List(RateLimit(3, 120), RateLimit(5, 3600)),
    passwordSubmit = List(RateLimit(5, 900), RateLimit(10, 3600)),
    passkeyAssertion = List(RateLimit(5, 300), RateLimit(10, 3600)),
    banDurationSeconds = 1800,
  )
