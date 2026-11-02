package versola.loadgen.antifraud

import zio.json.*

/** What one scenario measured. Everything here is a delta over the scenario's own run.
  *
  * @param smsSent         codes the SMS stub was asked to send: the cost the scenario had
  * @param smsRanges       how many ranges of numbers those went to
  * @param smsBusiestRange the most to one range
  * @param legitInRange    `mixed-attack` only: the legitimate users who dialled a number inside the attacked range
  * @param legitElsewhere `mixed-attack` only: the legitimate users who dialled any other
  * @param auth            `auth`'s antifraud counters over the run; `queueSize` is the largest seen
  */
case class ScenarioResult(
    scenario: String,
    seconds: Double,
    arrivals: Long,
    waited: Long,
    latencies: Map[String, Quantiles],
    otp: OtpOutcomes,
    legitInRange: Option[OtpOutcomes],
    legitElsewhere: Option[OtpOutcomes],
    failures: Map[String, Long],
    smsSent: Long,
    smsRanges: Int,
    smsBusiestRange: Long,
    auth: AuthAntifraudMetrics,
) derives JsonCodec

case class AntifraudReport(label: String, startedAt: String, scenarios: List[ScenarioResult]) derives JsonCodec

/** What the endpoints are held to when a run is laid beside the one made without antifraud. */
enum Verdict:
  case Pass, Fail, NoBaseline

/** One call of one scenario in two runs.
  *
  * `addedP99Ms` is the run's p99 less the baseline's, so it can be negative: two runs of the same system differ
  * by noise, and a figure that is not at least that large says nothing.
  */
case class Comparison(scenario: String, call: String, baseline: Option[Quantiles], run: Quantiles, addedP99Ms: Option[Double], verdict: Verdict)

object Comparison:
  /** The calls antifraud is on (a code requested, a code entered) and the ones it must stay off. */
  val protectedCalls: Set[String] = Set("otp-request", "otp-verify")
  val untouchedCalls: Set[String] = Set("token", "refresh")

  def compare(report: AntifraudReport, baseline: Option[AntifraudReport], ceilingMs: Double, toleranceMs: Double): List[Comparison] =
    for
      scenario <- report.scenarios
      (call, run) <- scenario.latencies.toList.sortBy(_._1)
    yield
      val base = baseline.flatMap(_.scenarios.find(_.scenario == scenario.scenario)).flatMap(_.latencies.get(call))
      val added = base.map(b => run.p99Ms - b.p99Ms)
      val verdict = added match
        case None => Verdict.NoBaseline
        case Some(ms) if protectedCalls(call) => if ms <= ceilingMs then Verdict.Pass else Verdict.Fail
        case Some(ms) => if math.abs(ms) <= toleranceMs then Verdict.Pass else Verdict.Fail
      Comparison(scenario = scenario.scenario, call = call, baseline = base, run = run, addedP99Ms = added, verdict = verdict)

/** A rule that is judged on a run alone, whatever it is compared with: `value` has to be at most `limit`. */
case class Check(scenario: String, name: String, value: Double, limit: Double, verdict: Verdict)

object Checks:
  private val attacks = Set("prefix-attack", "device-attack", "distributed-attack", "conversion-mimicking", "targeted-phone")

  private def share(part: Long, whole: Long): Double = if whole == 0L then 0.0 else part.toDouble / whole

  private def check(scenario: String, name: String, value: Double, limit: Double): Check =
    Check(scenario = scenario, name = name, value = value, limit = limit, verdict = if value <= limit then Verdict.Pass else Verdict.Fail)

  /** What an attack gets through (the share of the codes it asked for that were sent: the pumping loss), what
    * guessing gets through, and what legitimate users lose (the share of their requests refused). Judged on every
    * run, the baseline's included: with antifraud off the attacks are expected to fail, and that is the number the
    * other profiles are to bring down. */
  def of(report: AntifraudReport, attackSmsCeiling: Double, legitRefusalCeiling: Double): List[Check] =
    report.scenarios.flatMap: s =>
      val effectiveness =
        if attacks(s.scenario) then List(check(s.scenario, "attack: share of codes sent", share(s.smsSent, s.otp.requested), attackSmsCeiling))
        else if s.scenario == "verify-guessing" then List(check(s.scenario, "guesses accepted", s.otp.verified.toDouble, 0.0))
        else Nil
      val falsePositives =
        if s.scenario == "normal" then
          List(check(s.scenario, "legitimate: share refused", share(s.otp.refused, s.otp.requested), legitRefusalCeiling))
        else
          s.legitInRange.toList.map(o =>
            check(s.scenario, "legitimate in the attacked range: share refused", share(o.refused, o.requested), legitRefusalCeiling),
          ) ++
            s.legitElsewhere.toList.map(o =>
              check(s.scenario, "legitimate elsewhere: share refused", share(o.refused, o.requested), legitRefusalCeiling),
            )
      effectiveness ++ falsePositives

object ReportText:
  private def ms(value: Double): String = f"$value%.1f"

  def render(report: AntifraudReport, comparisons: List[Comparison], checks: List[Check], ceilingMs: Double, toleranceMs: Double): String =
    val latency =
      val header =
        "| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |\n|---|---|---:|---:|---:|---:|---:|---:|---|\n"
      header + comparisons
        .map: c =>
          s"| ${c.scenario} | ${c.call} | ${c.run.count} | ${c.run.errors} | ${ms(c.run.p50Ms)} | ${ms(c.run.p99Ms)} | " +
            s"${c.baseline.fold("-")(b => ms(b.p99Ms))} | ${c.addedP99Ms.fold("-")(ms)} | ${c.verdict} |"
        .mkString("\n")
    val outcomes =
      val header =
        "| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |\n" +
          "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n"
      header + report.scenarios
        .map: s =>
          val o = s.otp
          s"| ${s.scenario} | ${s.arrivals} | ${s.waited} | ${o.requested} | ${o.sent} | ${o.refused} | ${o.other} | ${o.verified} | " +
            s"${o.rejected} | ${o.notEntered} | ${o.failed} | ${s.smsSent} | ${s.smsRanges} | ${s.smsBusiestRange} |"
        .mkString("\n")
    val checkText =
      val header = "| scenario | check | value | limit | verdict |\n|---|---|---:|---:|---|\n"
      header + checks.map(c => f"| ${c.scenario} | ${c.name} | ${c.value}%.3f | ${c.limit}%.3f | ${c.verdict} |").mkString("\n")
    val legit = report.scenarios.flatMap: s =>
      List(s.legitInRange.map("in the attacked range" -> _), s.legitElsewhere.map("elsewhere" -> _)).flatten.map: (where, o) =>
        s"| ${s.scenario} | $where | ${o.requested} | ${o.sent} | ${o.refused} | ${o.verified} |"
    val legitText =
      if legit.isEmpty then ""
      else
        "\n#### Legitimate users during an attack\n\n| scenario | users | requested | sent | refused | verified |\n|---|---|---:|---:|---:|---:|\n" + legit.mkString(
          "\n",
        ) + "\n"
    val failures = report.scenarios.flatMap(s => s.failures.toList.sortBy(-_._2).map((reason, n) => s"- ${s.scenario}: $n x $reason"))
    val failureText = if failures.isEmpty then "" else s"\n#### Failures\n\n${failures.mkString("\n")}\n"
    val queue =
      val header =
        "| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |\n|---|---:|---:|---:|---:|---:|---:|\n"
      header + report.scenarios
        .map: s =>
          val a = s.auth
          val reasons = a.dropped.filter(_._2 > 0).map((reason, n) => s"$reason=${n.toLong}").mkString(", ")
          s"| ${s.scenario} | ${a.enqueued.toLong} | ${a.written.toLong} | ${a.droppedTotal.toLong}${
              if reasons.isEmpty then "" else s" ($reasons)"
            } | " +
            s"${a.smsRefused.toLong} | ${a.smsBudgetFull.toLong} | ${a.queueSize.toLong} |"
        .mkString("\n")
    s"""## antifraud loadgen: `${report.label}`
        #
        #> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.
        #
        #Added p99 is this run's p99 less the baseline's; ceiling ${ms(ceilingMs)} ms on ${Comparison.protectedCalls.toList.sorted.mkString(", ")},
        #within +-${ms(toleranceMs)} ms (noise) on ${Comparison.untouchedCalls.toList.sorted.mkString(", ")}.
        #
        #### Latency
        #
        #$latency
        #
        #### Outcomes and SMS
        #
        #$outcomes
        #$legitText
        #### Effectiveness and false positives
        #
        #Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
        #guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.
        #
        #$checkText
        #
        #### Event log (auth)
        #
        #$queue
        #$failureText""".stripMargin('#')
