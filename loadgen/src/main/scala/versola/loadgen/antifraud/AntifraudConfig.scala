package versola.loadgen.antifraud

import zio.config.magnolia.DeriveConfig
import zio.{Config, Duration}

/** What `role = antifraud` needs beyond the blocks every role shares: the OTP flow driven with and
  * without antifraud (#771, P0-7).
  *
  * Whether antifraud is on is a property of `auth`'s own config (the `antifraud` block), not of this
  * process: a run is made against one `auth` configuration and `label` says which, so the report of
  * the "off" run and the report of the "on" run can be laid side by side (`baseline-path`).
  *
  * @param label            names the `auth` configuration under test (`off`, `sms-budget`, ...); part of the report
  * @param clientId         a provisioned public client whose flow is phone + one-time code (`mobile-otp`)
  * @param redirectUri      the redirect URI that client was registered with
  * @param scope            requested on every authorization; needs `offline_access`, or there is nothing to refresh
  * @param authMetricsUrl   `auth`'s diagnostics `/metrics` (`DPORT`), read before and after each scenario
  * @param reportPath       where the JSON report is written
  * @param baselinePath     the JSON report of the run to compare this one with, normally the "off" run
  * @param scenarios        which scenarios run, in this order; all of [[AntifraudScenario]] when empty
  * @param maxInFlight      sessions started at once; an arrival past it waits, and the wait is counted
  * @param warmupSessions   unmeasured logins before the first scenario, so that it does not pay for the JIT and the pool
  * @param queueSampleEvery how often `antifraud_event_queue_size` is read while a scenario runs
  * @param controlTolerance added p99 on `/token` and refresh above which the control is reported as failed: the
  *                         measurement's noise floor, since the true figure is zero (nothing of antifraud is on that path)
  */
case class AntifraudConfig(
    label: String,
    clientId: String,
    redirectUri: String,
    scope: String,
    authMetricsUrl: String,
    reportPath: String,
    baselinePath: Option[String],
    scenarios: List[AntifraudScenario],
    otp: OtpDeliveryConfig,
    smsStub: SmsStubConfig,
    numbers: NumberPlanConfig,
    normal: NormalTrafficConfig,
    prefixAttack: AttackConfig,
    deviceAttack: AttackConfig,
    tokenControl: TokenControlConfig,
    maxInFlight: Int,
    warmupSessions: Int,
    queueSampleEvery: Duration,
    controlTolerance: Duration,
    addedLatencyCeiling: Duration,
    seed: Option[Long],
):
  def selected: List[AntifraudScenario] = if scenarios.isEmpty then AntifraudScenario.values.toList else scenarios

/** How the code of a one-time password reaches this process.
  *
  * `stub`: `auth` runs with `env = prod`, which is the only mode that sends a code, and its `otp-provider`
  * points at [[SmsStubConfig]]; the code is read from what the stub received, as a phone would. `fixed`: a
  * non-prod `auth` generates the same code every time and sends nothing (so no send is ever counted).
  */
case class OtpDeliveryConfig(source: OtpSource, length: Int)

enum OtpSource(val wire: String):
  case Stub extends OtpSource("stub")
  case Fixed extends OtpSource("fixed")

object OtpSource:
  given DeriveConfig[OtpSource] = DeriveConfig[String].mapOrFail: value =>
    values.find(_.wire == value).toRight(Config.Error.InvalidData(message = s"unknown otp source: $value; expected stub or fixed"))

/** The SMS provider `auth` is configured with, which counts what it is asked to send. */
case class SmsStubConfig(bindHost: String, port: Int, responseDelay: Duration)

/** The numbers the scenarios dial: `dialCode` plus a national number of `nationalLength` digits that starts with
  * one of `leads`. They have to be numbers the tenant's `phoneRegions` accept (the blueprint's: `+49`, 11 digits,
  * `1[5-7]...`).
  *
  * @param rangeLength digits of the number, country code included, that make a range for the SMS budget
  *                    (`antifraud.sms-budget.prefix-length`); the report groups sends by it
  */
case class NumberPlanConfig(
    dialCode: String,
    nationalLength: Int,
    leads: List[String],
    rangeLength: Int,
)

/** Ordinary users: a code is requested and, for `conversion` of them, entered; a verified login exchanges its
  * code and refreshes between `refreshesMin` and `refreshesMax` times (the target profile is 5-10 refreshes per
  * code exchange).
  */
case class NormalTrafficConfig(
    rate: Double,
    duration: Duration,
    conversion: Double,
    refreshesMin: Int,
    refreshesMax: Int,
)

/** `rate` codes requested per second for `duration`, none of them verified. */
case class AttackConfig(rate: Double, duration: Duration)

/** The control: `sessions` logins made unmeasured, then `rate` refreshes per second for `duration` over them. */
case class TokenControlConfig(sessions: Int, rate: Double, duration: Duration)

enum AntifraudScenario(val wire: String):
  /** Ordinary traffic: a code requested, mostly entered, then tokens. */
  case Normal extends AntifraudScenario("normal")

  /** Only `/token` and refresh: the endpoints antifraud must not touch. */
  case TokenControl extends AntifraudScenario("token-control")

  /** Many codes to numbers of one range, almost none verified: SMS pumping. */
  case PrefixAttack extends AntifraudScenario("prefix-attack")

  /** One source cycling through many numbers, spread over all ranges, none verified. */
  case DeviceAttack extends AntifraudScenario("device-attack")

object AntifraudScenario:
  def fromWire(value: String): Option[AntifraudScenario] = values.find(_.wire == value)

  given DeriveConfig[AntifraudScenario] = DeriveConfig[String].mapOrFail: value =>
    fromWire(value).toRight(
      Config.Error.InvalidData(message = s"unknown antifraud scenario: $value; expected one of ${values.map(_.wire).mkString(", ")}"),
    )
