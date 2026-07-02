package versola.central.configuration.challenges

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class RateLimit(maxAttempts: Int, windowSeconds: Int) derives Schema, JsonCodec

case class SubmissionLimits(
    otpRequest: List[RateLimit] = Nil,
    otpSubmit: List[RateLimit] = Nil,
    passwordSubmit: List[RateLimit] = Nil,
    passkeyAssertion: List[RateLimit] = Nil,
    banDurationSeconds: Int = 0,
) derives Schema, JsonCodec

object SubmissionLimits:
  val empty: SubmissionLimits = SubmissionLimits()
