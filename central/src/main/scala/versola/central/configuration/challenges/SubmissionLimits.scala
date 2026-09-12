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

  /** Seeded on every new tenant unless the caller overrides it in the create-tenant request:
    * tight enough to slow down credential stuffing and OTP brute force, loose enough not to
    * lock out a genuine user who mistypes a password or code a few times. These are the
    * same values every tenant used to get implicitly via `BootstrapService`, now made an
    * explicit, tenant-editable default instead of a value only the bootstrap tenant saw.
    */
  val recommended: SubmissionLimits = SubmissionLimits(
    otpRequest = List(RateLimit(2, 60), RateLimit(5, 3600)),
    otpSubmit = List(RateLimit(3, 120), RateLimit(5, 3600)),
    passwordSubmit = List(RateLimit(5, 900), RateLimit(10, 3600)),
    passkeyAssertion = List(RateLimit(5, 300), RateLimit(10, 3600)),
    banDurationSeconds = 1800,
  )

  /** A category left empty provides no throttling at all for that submission type, so it
    * fails closed rather than being accepted as a deliberate "no limit" choice.
    */
  def isConfigured(limits: SubmissionLimits): Boolean =
    limits.otpRequest.nonEmpty &&
      limits.otpSubmit.nonEmpty &&
      limits.passwordSubmit.nonEmpty &&
      limits.passkeyAssertion.nonEmpty &&
      limits.banDurationSeconds > 0
