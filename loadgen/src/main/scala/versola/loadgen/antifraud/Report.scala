package versola.loadgen.antifraud

import zio.json.*

/** What one scenario measured. Everything here is a delta over the scenario's own run.
  *
  * @param smsSent         codes the SMS stub was asked to send: the cost the scenario had
  * @param smsRanges       how many ranges of numbers those went to
  * @param smsBusiestRange the most to one range
  * @param legitInRange    `mixed-attack` only: the legitimate users who dialled a number inside the attacked range
  * @param legitElsewhere `mixed-attack` only: the legitimate users who dialled any other
  * @param auth            `auth`'s antifraud counters over the run (summed over the instances); `queueSize` is the largest seen
  * @param challenge       what the challenges cost the client (see [[ChallengeSnapshot]]); none when none was put
  * @param fakeTiming      `fake-timing` only: the two groups' response times and the verdict
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
    challenge: Option[ChallengeSnapshot] = None,
    fakeTiming: Option[FakeTimingResult] = None,
) derives JsonCodec

/** @param client    the kind of client that asked for the codes (`script` or `plain`)
  * @param instances the `auth` instances that were addressed
  */
case class AntifraudReport(
    label: String,
    startedAt: String,
    scenarios: List[ScenarioResult],
    client: String = "script",
    instances: List[String] = Nil,
) derives JsonCodec

/** One group of the `fake-timing` scenario: the requests that were made, how they ended, how many numbers were sent a code
  * (what reached the SMS stub), and how long `auth` took over the call that decides, over the requests that were sent.
  */
case class TimingGroup(
    requests: Long,
    sent: Long,
    refused: Long,
    other: Long,
    reachedSms: Long,
    p50Ms: Double,
    p90Ms: Double,
    p99Ms: Double,
    maxMs: Double,
) derives JsonCodec

/** The verdict of the fake-timing check: `verdict` is `Pass` or `Fail`, `problems` says why it failed. */
case class FakeTimingResult(
    fake: TimingGroup,
    real: TimingGroup,
    medianGapMs: Double,
    p90GapMs: Double,
    p99GapMs: Double,
    toleranceMs: Double,
    problems: List[String],
    verdict: String,
) derives JsonCodec:
  def passed: Boolean = verdict == "Pass"

  /** The problems that are of the run and not of the finding: too few requests that were sent, no real send at the stub. */
  def setupProblems: List[String] = problems.filter(p => p.startsWith("no real number") || p.startsWith("only "))

object FakeTimingCheck:
  /** Fewer requests of a group that were sent than this say nothing about its median. */
  val MinSamples = 20L

  /** Judges two groups of timings. It fails when
    *  - a fake number was sent a code (the list entry is missing, or does not match the numbers, so the groups are the same),
    *  - no real number was sent one (the SMS stub is not what `auth` sends to, so the real send was not the one timed),
    *  - either group has fewer than `minSamples` requests that were sent, or
    *  - the medians differ by more than `toleranceMs`.
    * The tails are reported (`p90GapMs`, `p99GapMs`) and not judged: a p99 of a few hundred samples is noise. */
  def judge(fake: TimingGroup, real: TimingGroup, toleranceMs: Double, minSamples: Long): FakeTimingResult =
    val gap = fake.p50Ms - real.p50Ms
    val problems = List(
      Option.when(fake.reachedSms > 0)(
        s"${fake.reachedSms} of the fake numbers were sent a code: the fake entry is not seeded, has not been reloaded yet (auth reads the lists every few seconds), or does not match these numbers",
      ),
      Option.when(real.sent > 0 && real.reachedSms == 0)("no real number was sent a code: the SMS stub is not what auth sends to (env = prod, otp-provider)"),
      Option.when(fake.sent < minSamples)(s"only ${fake.sent} fake requests were sent (at least $minSamples are needed)"),
      Option.when(real.sent < minSamples)(s"only ${real.sent} real requests were sent (at least $minSamples are needed)"),
      Option.when(math.abs(gap) > toleranceMs)(f"the medians differ by $gap%.1f ms, more than the tolerance of $toleranceMs%.1f ms"),
    ).flatten
    FakeTimingResult(
      fake = fake,
      real = real,
      medianGapMs = gap,
      p90GapMs = fake.p90Ms - real.p90Ms,
      p99GapMs = fake.p99Ms - real.p99Ms,
      toleranceMs = toleranceMs,
      problems = problems,
      verdict = if problems.isEmpty then "Pass" else "Fail",
    )

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
  val protectedCalls: Set[String] = Set("otp-request", "otp-resend", "otp-verify")
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
      val timing = s.fakeTiming.toList.flatMap: t =>
        List(
          check(s.scenario, "fake vs real: gap of the medians (ms)", math.abs(t.medianGapMs), t.toleranceMs),
          check(s.scenario, "fake numbers sent a code", t.fake.reachedSms.toDouble, 0.0),
          check(s.scenario, "enough sent requests, and the SMS stub reached (problems)", t.setupProblems.size.toDouble, 0.0),
        )
      effectiveness ++ falsePositives ++ timing

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
        "| scenario | arrivals | waited | requested | sent | refused | challenged | solved | paid by waiting | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |\n" +
          "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n"
      header + report.scenarios
        .map: s =>
          val o = s.otp
          s"| ${s.scenario} | ${s.arrivals} | ${s.waited} | ${o.requested} | ${o.sent} | ${o.refused} | ${o.challenged} | ${o.solved} | ${o.paidByWaiting} | ${o.other} | ${o.verified} | " +
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
    val challengeText =
      val rows = report.scenarios.flatMap(s => s.challenge.map(s.scenario -> _))
      if rows.isEmpty then ""
      else
        val header =
          "| scenario | challenges put | solved | unanswered | solve p50 ms | solve p99 ms | hashes | hashes p50 | hashes p99 | left pending | honoured after the pause | asks too early | request with solving or waiting: p50 ms | p99 ms |\n" +
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n"
        "\n#### Challenges\n\n" +
          "`challenged` in the outcomes is requests put a challenge (or, for a plain client, left pending); `solved` those that did the work and were sent the code, `paid by waiting` those of a plain client that waited the pause. " +
          "None of them is a refusal. The solve time is this process's CPU, not the server's; the latencies above are the server's alone, and the last two columns are what the person waited.\n\n" +
          header + rows
            .map: (scenario, c) =>
              s"| $scenario | ${c.received} | ${c.solved} | ${c.unanswered} | ${ms(c.solveP50Ms)} | ${ms(c.solveP99Ms)} | ${c.hashes} | ${c.hashesP50} | ${c.hashesP99} | " +
                s"${c.paused} | ${c.pauseHonoured} | ${c.earlyAsks} | ${ms(c.requestTotal.p50Ms)} | ${ms(c.requestTotal.p99Ms)} |"
            .mkString("\n") + "\n"
    val fakeText =
      val rows = report.scenarios.flatMap(s => s.fakeTiming.map(s.scenario -> _))
      if rows.isEmpty then ""
      else
        val group = (name: String, g: TimingGroup) =>
          s"| $name | ${g.requests} | ${g.sent} | ${g.refused} | ${g.other} | ${g.reachedSms} | ${ms(g.p50Ms)} | ${ms(g.p90Ms)} | ${ms(g.p99Ms)} | ${ms(g.maxMs)} |"
        "\n#### Fake send timing\n\n" +
          "Response time of the call that decides, over the requests that were sent, to numbers on a `fake` list entry and to real ones. " +
          "The medians must not differ by more than the tolerance: a fake that answers differently tells the person who times it which codes are never delivered.\n\n" +
          rows
            .map: (scenario, t) =>
              s"`$scenario`: median gap ${ms(t.medianGapMs)} ms (tolerance ${ms(t.toleranceMs)}), p90 gap ${ms(t.p90GapMs)} ms, p99 gap ${ms(t.p99GapMs)} ms: **${t.verdict}**\n\n" +
                "| numbers | requests | sent | refused | other | sent a code (at the stub) | p50 ms | p90 ms | p99 ms | max ms |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n" +
                group("fake", t.fake) + "\n" + group("real", t.real) + "\n" +
                (if t.problems.isEmpty then "" else "\n" + t.problems.map(p => s"- $p").mkString("\n") + "\n")
            .mkString("\n")
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
    val clientText = s"Client: `${report.client}`; auth instances: ${if report.instances.isEmpty then "1" else report.instances.mkString(", ")}."
    s"""## antifraud loadgen: `${report.label}`
        #
        #> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.
        #
        #$clientText
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
        #$challengeText$fakeText$failureText""".stripMargin('#')
