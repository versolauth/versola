package versola.loadgen.antifraud

import zio.config.magnolia.DeriveConfig
import zio.{Config, Duration, durationInt}

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
  * @param authMetricsUrls  the diagnostics of every `auth` instance when there are several (`targets.auth-urls`): the
  *                         counters are summed over them, and the queue sizes. Empty: just `authMetricsUrl`
  * @param reportPath       where the JSON report is written
  * @param baselinePath     the JSON report of the run to compare this one with, normally the "off" run
  * @param scenarios        which scenarios run, in this order; all of [[AntifraudScenario]] when empty
  * @param maxInFlight      sessions started at once; an arrival past it waits, and the wait is counted
  * @param warmupSessions   unmeasured logins before the first scenario, so that it does not pay for the JIT and the pool
  * @param queueSampleEvery how often `antifraud_event_queue_size` is read while a scenario runs
  * @param sources          where the requests come from, as `auth` sees it (see [[SourcesConfig]])
  * @param attackSmsCeiling the most of an attack's requested codes that may be sent (0.1: nine in ten must be stopped);
  *                         an attack scenario above it fails the effectiveness check whatever the latency says
  * @param legitRefusalCeiling the most of legitimate users' requests that may be refused (0.01); the check of the
  *                         false positives, over `normal` and over the legitimate users of `mixed-attack`
  * @param challenge        how the client answers a challenge, and how long a plain client waits (see [[ChallengeClientConfig]])
  * @param fakeTiming       the `fake-timing` scenario (see [[FakeTimingConfig]])
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
    distributedAttack: AttackConfig,
    tokenControl: TokenControlConfig,
    mixed: MixedConfig,
    mimicking: MimickingConfig,
    targeted: AttackConfig,
    guessing: GuessingConfig,
    attackSmsCeiling: Double,
    legitRefusalCeiling: Double,
    maxInFlight: Int,
    warmupSessions: Int,
    queueSampleEvery: Duration,
    controlTolerance: Duration,
    addedLatencyCeiling: Duration,
    seed: Option[Long],
    sources: SourcesConfig,
    authMetricsUrls: List[String] = Nil,
    challenge: ChallengeClientConfig = ChallengeClientConfig(),
    fakeTiming: FakeTimingConfig = FakeTimingConfig(),
):
  /** The scenarios that run: the ones named, or all but `fake-timing` (it needs a list entry seeded beforehand, and is
    * only run when asked for by name). */
  def selected: List[AntifraudScenario] =
    if scenarios.isEmpty then AntifraudScenario.values.toList.filterNot(_ == AntifraudScenario.FakeTiming) else scenarios

  /** The diagnostics endpoints of the instances under test. */
  def metricsUrls: List[String] = if authMetricsUrls.nonEmpty then authMetricsUrls else List(authMetricsUrl)

/** How the code of a one-time password reaches this process, and what kind of client asks for it.
  *
  * `stub`: `auth` runs with `env = prod`, which is the only mode that sends a code, and its `otp-provider`
  * points at [[SmsStubConfig]]; the code is read from what the stub received, as a phone would. `fixed`: a
  * non-prod `auth` generates the same code every time and sends nothing (so no send is ever counted).
  *
  * `client` is the kind of client that asks for the code: `script` sends the form the way the pages with script do
  * (`proof_inline`) and answers a proof-of-work challenge by solving it (see [[OtpClient]]); `plain` posts the form with
  * none of that, and a request that is left pending waits the pause and asks again.
  */
case class OtpDeliveryConfig(source: OtpSource, length: Int, client: OtpClient = OtpClient.Script)

/** What asks for the codes. */
enum OtpClient(val wire: String):
  /** A browser with script: submits inline, is given a proof of work to solve when it is challenged, and solves it. */
  case Script extends OtpClient("script")

  /** A form post with no script: cannot be given a proof, so a challenged request is left pending and its ask for the
    * code is honoured only after `antifraud.challenge.pause`. */
  case Plain extends OtpClient("plain")

object OtpClient:
  given DeriveConfig[OtpClient] = DeriveConfig[String].mapOrFail: value =>
    values.find(_.wire == value).toRight(Config.Error.InvalidData(message = s"unknown otp client: $value; expected script or plain"))

/** How the client answers a challenge.
  *
  * @param maxRounds     proofs answered in a row for one request before the client gives up (3: the browser's)
  * @param maxDifficulty the most zero bits the client will work for; a challenge above it is left unanswered and counted
  *                      so (what the client is not willing to pay, and a bound on a search that would not end)
  * @param pause         what `auth` makes a plain client wait (`antifraud.challenge.pause` of the auth profile: keep them
  *                      equal): the ask for the code is made this long after the request was left pending
  * @param pauseSlack    added to `pause` so that the ask is not made a moment too early, and the wait between asks that were
  * @param maxAsks       asks a plain client makes before it gives up on a pending request (an `auth` without the pause
  *                      never honours one)
  * @param solverThreads proofs solved at once, a thread each; 0 is the number of processors. The solving is this process's
  *                      CPU: with more challenges than it can solve the wait for a thread is not in the solve time,
  *                      but it is in `requestTotal`
  */
case class ChallengeClientConfig(
    maxRounds: Int = 3,
    maxDifficulty: Int = 26,
    pause: Duration = 20.seconds,
    pauseSlack: Duration = 1.second,
    maxAsks: Int = 3,
    solverThreads: Int = 0,
)

/** The `fake-timing` scenario: `requests` requests for a code to numbers on a `fake` list entry, and as many to real
  * ones, at `rate` a second together, alternating. The entry is made before the run (`loadgen-runbook`:
  * `scripts/antifraud-local`), by prefix (`fake-prefix`: every number starting with it is fake) or by number
  * (`fake-numbers`, used in turn).
  *
  * @param warmup    real requests first, unmeasured: `auth` makes a fake send wait as long as the real ones have been taking,
  *                  and has to have seen some
  * @param tolerance the largest difference of the two medians that passes: what a person timing the answers could not tell
  */
case class FakeTimingConfig(
    requests: Int = 200,
    rate: Double = 5.0,
    tolerance: Duration = 25.millis,
    warmup: Int = 20,
    fakePrefix: Option[String] = None,
    fakeNumbers: List[String] = Nil,
)

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

/** Where the requests come from, as `auth` sees it. Every request of this process leaves one socket, so without
  * this the whole run is one client to the velocity counters and the lists; with `header` set, each session carries a
  * source address in it (the header the tenant's `ipHeader` names, which the stack has to take from this process's
  * address), drawn as follows: a legitimate session from a pool of `legit` addresses (a few users share one, as behind
  * a NAT), a distributed attack from a pool of `distributed` addresses, and every other attack from one address of its
  * own, so that a source an earlier scenario got blocked does not carry over. An empty `header` sends none.
  */
case class SourcesConfig(header: String, legit: Int, distributed: Int):
  def enabled: Boolean = header.nonEmpty

/** Legitimate sign-ins at `normalRate` while an attack on one range runs at `attackRate`, for `duration`;
  * `legitInRangeShare` of the legitimate users dial a number inside the attacked range. What a closed range
  * does to them is the cost of the budget.
  */
case class MixedConfig(normalRate: Double, attackRate: Double, duration: Duration, legitInRangeShare: Double)

/** An attack on one range whose sender enters the code for `verifyShare` of the numbers, to keep the conversion
  * of the range up.
  */
case class MimickingConfig(rate: Double, duration: Duration, verifyShare: Double)

/** Codes requested for new numbers and then guessed at: `attempts` wrong codes for each. */
case class GuessingConfig(rate: Double, duration: Duration, attempts: Int)

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

  /** Many sources (a botnet, a proxy pool) asking for codes to numbers of one range, none verified: what a per-source
    * limit does not see. */
  case DistributedAttack extends AntifraudScenario("distributed-attack")

  /** Legitimate sign-ins, some inside the range, while the range is attacked: the false positives. */
  case MixedAttack extends AntifraudScenario("mixed-attack")

  /** An attack on one range that enters the codes of a share of the numbers to keep the conversion up. */
  case ConversionMimicking extends AntifraudScenario("conversion-mimicking")

  /** Codes asked for again and again for one number: SMS bombing, a lockout of its owner. */
  case TargetedPhone extends AntifraudScenario("targeted-phone")

  /** Wrong codes entered for numbers a code was requested for. */
  case VerifyGuessing extends AntifraudScenario("verify-guessing")

  /** Codes asked for numbers on a `fake` list entry and for real numbers, and how long `auth` took to answer each: a fake
    * send that answers differently from a real one tells the person who times it which codes are never delivered. Run only
    * when named, since it needs the entry. */
  case FakeTiming extends AntifraudScenario("fake-timing")

object AntifraudScenario:
  def fromWire(value: String): Option[AntifraudScenario] = values.find(_.wire == value)

  given DeriveConfig[AntifraudScenario] = DeriveConfig[String].mapOrFail: value =>
    fromWire(value).toRight(
      Config.Error.InvalidData(message = s"unknown antifraud scenario: $value; expected one of ${values.map(_.wire).mkString(", ")}"),
    )
