package versola.loadgen.antifraud

import zio.json.*

/** What one scenario measured. Everything here is a delta over the scenario's own run.
  *
  * @param smsSent         codes the SMS stub was asked to send: the cost the scenario had
  * @param smsRanges       how many ranges of numbers those went to
  * @param smsBusiestRange the most to one range
  * @param auth            `auth`'s antifraud counters over the run; `queueSize` is the largest seen
  */
case class ScenarioResult(
    scenario: String,
    seconds: Double,
    arrivals: Long,
    waited: Long,
    latencies: Map[String, Quantiles],
    otp: OtpOutcomes,
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

object ReportText:
  private def ms(value: Double): String = f"$value%.1f"

  def render(report: AntifraudReport, comparisons: List[Comparison], ceilingMs: Double, toleranceMs: Double): String =
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
    val failures = report.scenarios.flatMap(s => s.failures.toList.sortBy(-_._2).map((reason, n) => s"- ${s.scenario}: $n x $reason"))
    val failureText = if failures.isEmpty then "" else s"\n### Failures\n\n${failures.mkString("\n")}\n"
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
        #
        #### Event log (auth)
        #
        #$queue
        #$failureText""".stripMargin('#')
