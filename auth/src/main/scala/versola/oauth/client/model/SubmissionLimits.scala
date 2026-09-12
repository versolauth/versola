package versola.oauth.client.model

import zio.json.JsonCodec

case class RateLimit(maxAttempts: Int, windowSeconds: Int) derives JsonCodec

case class SubmissionLimits(
    otpRequest: List[RateLimit] = Nil,
    otpSubmit: List[RateLimit] = Nil,
    passwordSubmit: List[RateLimit] = Nil,
    passkeyAssertion: List[RateLimit] = Nil,
    banDurationSeconds: Int = 0,
) derives JsonCodec

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
