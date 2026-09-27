package versola.central.configuration.challenges

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class RateLimit(maxAttempts: Int, windowSeconds: Int) derives Schema, JsonCodec

/** No member defaults: a category left out of the request would decode to `Nil`, which is
  * this tenant running that submission type unthrottled -- the caller has to say so rather
  * than arrive at it by omission.
  */
case class SubmissionLimits(
    otpRequest: List[RateLimit],
    otpSubmit: List[RateLimit],
    passwordSubmit: List[RateLimit],
    passkeyAssertion: List[RateLimit],
    banDurationSeconds: Int,
) derives Schema, JsonCodec

object SubmissionLimits:
  val empty: SubmissionLimits = SubmissionLimits(
    otpRequest = Nil,
    otpSubmit = Nil,
    passwordSubmit = Nil,
    passkeyAssertion = Nil,
    banDurationSeconds = 0,
  )

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
    * fails closed rather than being accepted as a deliberate "no limit" choice. A non-positive
    * `maxAttempts`/`windowSeconds` on any tier is rejected the same way -- auth's
    * `ThrottlePolicy.requireValid` throws on those at evaluation time, so letting one through
    * here would crash that tenant's submission checks instead of falling back to a safe
    * default.
    */
  def isConfigured(limits: SubmissionLimits): Boolean =
    def tiersConfigured(tiers: List[RateLimit]): Boolean =
      tiers.nonEmpty && tiers.forall(tier => tier.maxAttempts > 0 && tier.windowSeconds > 0)

    tiersConfigured(limits.otpRequest) &&
      tiersConfigured(limits.otpSubmit) &&
      tiersConfigured(limits.passwordSubmit) &&
      tiersConfigured(limits.passkeyAssertion) &&
      limits.banDurationSeconds > 0
