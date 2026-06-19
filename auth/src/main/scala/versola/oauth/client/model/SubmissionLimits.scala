package versola.oauth.client.model

import zio.json.JsonCodec

case class RateLimit(maxAttempts: Int, windowSeconds: Int) derives JsonCodec

case class SubmissionLimits(
    otpRequest: List[RateLimit] = Nil,
    otpSubmit: List[RateLimit] = Nil,
    passwordSubmit: List[RateLimit] = Nil,
    banDurationSeconds: Int = 0,
) derives JsonCodec

object SubmissionLimits:
  val empty: SubmissionLimits = SubmissionLimits()
