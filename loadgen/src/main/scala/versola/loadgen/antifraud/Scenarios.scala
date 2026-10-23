package versola.loadgen.antifraud

import versola.loadgen.protocol.{AuthClient, Tokens}
import zio.http.Client
import zio.{Clock, Duration, Queue, Task, UIO, ZIO}

import java.util.SplittableRandom

/** Runs the scenarios of `role = antifraud` against one `auth`, and says what each did.
  *
  * Every scenario is an arrival process at a fixed rate (the sessions are started on a schedule, not when the
  * last one is done, so a slow system does not slow the offered load down) over fresh numbers.
  */
final class Scenarios(
    client: Client,
    auth: AuthClient,
    config: AntifraudConfig,
    stub: SmsStub,
    numbers: PhoneNumbers,
    random: SplittableRandom,
):

  def run(scenario: AntifraudScenario): Task[ScenarioResult] =
    scenario match
      case AntifraudScenario.Normal => normal
      case AntifraudScenario.PrefixAttack => prefixAttack
      case AntifraudScenario.DeviceAttack => deviceAttack
      case AntifraudScenario.TokenControl => tokenControl
      case AntifraudScenario.MixedAttack => mixedAttack
      case AntifraudScenario.ConversionMimicking => conversionMimicking
      case AntifraudScenario.TargetedPhone => targetedPhone
      case AntifraudScenario.VerifyGuessing => verifyGuessing

  /** Logins nobody measures, so that the first scenario does not pay for the JIT, the connections and the caches. */
  def warmup: Task[Unit] =
    val flows = OtpFlows(auth = auth, config = config, stub = stub, measurements = Measurements(), tally = Tally(), measureOtp = false)
    val phones = List.fill(config.warmupSessions)(numbers.any())
    ZIO.foreachParDiscard(phones)(phone => flows.login(phone).ignore).withParallelism(4)

  private case class Draw(phone: String, verifies: Boolean, refreshes: Int, inRange: Boolean)

  private def legitDraw(inRangeShare: Double, range: Option[String]): UIO[Draw] =
    val settings = config.normal
    ZIO.succeed:
      val inRange = range.isDefined && random.nextDouble() < inRangeShare
      Draw(
        phone = range.filter(_ => inRange).fold(numbers.any())(numbers.in),
        verifies = random.nextDouble() < settings.conversion,
        refreshes = settings.refreshesMin + random.nextInt(settings.refreshesMax - settings.refreshesMin + 1),
        inRange = inRange,
      )

  /** A legitimate user: asks for a code, enters it unless they give up, and uses the tokens. */
  private def legitSession(flows: OtpFlows, tally: Tally, draw: Draw): UIO[Unit] =
    flows
      .requestOtp(draw.phone)
      .flatMap:
        case sent: OtpFlows.Requested.Sent if draw.verifies =>
          flows.verify(sent, draw.phone).flatMap:
            case Some(granted) =>
              flows.exchange(granted).flatMap(tokens => ZIO.foldLeft(1 to draw.refreshes)(tokens)((current, _) => flows.refresh(current))).unit
            case None => ZIO.unit
        case _: OtpFlows.Requested.Sent => ZIO.succeed(tally.notEntered.increment())
        case _ => ZIO.unit
      .ignore

  private def normal: Task[ScenarioResult] =
    val settings = config.normal
    measured(AntifraudScenario.Normal): run =>
      val flows = run.flows(run.main)
      Arrivals
        .run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(legitDraw(0.0, None))(draw =>
          legitSession(flows, run.main, draw),
        )
        .map(Outcome(_, None, None))

  /** Many codes to numbers of one range, none verified. The range is drawn once per run, so that a run made after
    * another does not meet a range the first one closed (the budget remembers an hour). */
  private def prefixAttack: Task[ScenarioResult] =
    val range = numbers.anyRange()
    measured(AntifraudScenario.PrefixAttack): run =>
      val flows = run.flows(run.main)
      Arrivals
        .run(rate = config.prefixAttack.rate, duration = config.prefixAttack.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed(numbers.in(range)),
        )(phone => flows.requestOtp(phone).ignore)
        .map(Outcome(_, None, None))

  /** One source asking for codes to numbers all over, none verified. */
  private def deviceAttack: Task[ScenarioResult] =
    measured(AntifraudScenario.DeviceAttack): run =>
      val flows = run.flows(run.main)
      Arrivals
        .run(rate = config.deviceAttack.rate, duration = config.deviceAttack.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed(numbers.any()),
        )(phone => flows.requestOtp(phone).ignore)
        .map(Outcome(_, None, None))

  /** Legitimate users, `legit-in-range-share` of them with a number inside the range that is being attacked, at the
    * same time as the attack. The attack's own outcomes are the scenario's; the legitimate users' are kept apart, in
    * the range and outside it. */
  private def mixedAttack: Task[ScenarioResult] =
    val settings = config.mixed
    val range = numbers.anyRange()
    measured(AntifraudScenario.MixedAttack): run =>
      val inRange = Tally()
      val elsewhere = Tally()
      val attacker = run.flows(run.main)
      val inFlows = run.flows(inRange)
      val outFlows = run.flows(elsewhere)
      val attack = Arrivals.run(rate = settings.attackRate, duration = settings.duration, maxInFlight = config.maxInFlight)(
        ZIO.succeed(numbers.in(range)),
      )(phone => attacker.requestOtp(phone).ignore)
      val legit = Arrivals.run(rate = settings.normalRate, duration = settings.duration, maxInFlight = config.maxInFlight)(
        legitDraw(settings.legitInRangeShare, Some(range)),
      )(draw => if draw.inRange then legitSession(inFlows, inRange, draw) else legitSession(outFlows, elsewhere, draw))
      attack.zipPar(legit).map((a, l) =>
        Outcome(ArrivalSummary(arrivals = a.arrivals + l.arrivals, waited = a.waited + l.waited), Some(inRange), Some(elsewhere)),
      )

  /** An attack on one range whose sender enters the code of `verify-share` of the numbers, so that the range keeps
    * a conversion. */
  private def conversionMimicking: Task[ScenarioResult] =
    val settings = config.mimicking
    val range = numbers.anyRange()
    measured(AntifraudScenario.ConversionMimicking): run =>
      val flows = run.flows(run.main)
      Arrivals
        .run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed((numbers.in(range), random.nextDouble() < settings.verifyShare)),
        ): (phone, verifies) =>
          flows.requestOtp(phone).flatMap {
            case sent: OtpFlows.Requested.Sent if verifies => flows.verify(sent, phone).unit
            case _ => ZIO.unit
          }.ignore
        .map(Outcome(_, None, None))

  /** One number asked for codes again and again. */
  private def targetedPhone: Task[ScenarioResult] =
    val phone = numbers.any()
    measured(AntifraudScenario.TargetedPhone): run =>
      val flows = run.flows(run.main)
      Arrivals
        .run(rate = config.targeted.rate, duration = config.targeted.duration, maxInFlight = config.maxInFlight)(ZIO.succeed(phone))(number =>
          flows.requestOtp(number).ignore,
        )
        .map(Outcome(_, None, None))

  /** A code requested for a new number and then `attempts` random codes entered. A guess that is taken counts as
    * `verified`. */
  private def verifyGuessing: Task[ScenarioResult] =
    val settings = config.guessing
    measured(AntifraudScenario.VerifyGuessing): run =>
      val flows = run.flows(run.main)
      val digits = config.otp.length
      Arrivals
        .run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed((numbers.any(), List.fill(settings.attempts)(List.fill(digits)(random.nextInt(10)).mkString))),
        ): (phone, guesses) =>
          flows.requestOtp(phone).flatMap {
            case sent: OtpFlows.Requested.Sent => ZIO.foreachDiscard(guesses)(guess => flows.enter(sent, guess))
            case _ => ZIO.unit
          }.ignore
        .map(Outcome(_, None, None))

  /** `/token` and refresh alone: sessions are made first (only their code exchange is measured), then refreshed at
    * a fixed rate. A session is refreshed by one fiber at a time, since a token used twice is a reuse. */
  private def tokenControl: Task[ScenarioResult] =
    val settings = config.tokenControl
    measured(AntifraudScenario.TokenControl): run =>
      val flows = run.flows(run.main, measureOtp = false)
      for
        pool <- Queue.unbounded[Tokens]
        phones = List.fill(settings.sessions)(numbers.any())
        _ <- ZIO.foreachParDiscard(phones)(phone => flows.login(phone).flatMap(ZIO.foreachDiscard(_)(pool.offer)).ignore).withParallelism(8)
        made <- pool.size
        _ <- ZIO.fail(ControlSetupFailed(settings.sessions, run.main.failures)).when(made == 0)
        summary <- Arrivals.run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(pool.take): tokens =>
          flows.refresh(tokens).flatMap(pool.offer).ignore
      yield Outcome(summary, None, None)

  /** What a scenario's body is given: the measurements it records into, the tally of its own requests, and a way to
    * make flows that count into another tally. */
  private final class Run(val measurements: Measurements, val main: Tally):
    def flows(tally: Tally, measureOtp: Boolean = true): OtpFlows =
      OtpFlows(auth = auth, config = config, stub = stub, measurements = measurements, tally = tally, measureOtp = measureOtp)

  private case class Outcome(summary: ArrivalSummary, legitInRange: Option[Tally], legitElsewhere: Option[Tally])

  private def measured(scenario: AntifraudScenario)(body: Run => Task[Outcome]): Task[ScenarioResult] =
    val run = Run(Measurements(), Tally())
    for
      before <- AuthAntifraudMetrics.read(client, config.authMetricsUrl)
      stubBefore = stub.sentByRange
      sentBefore = stub.sent
      started <- Clock.nanoTime
      (outcome, peak) <- QueueSampler.around(client = client, url = config.authMetricsUrl, every = config.queueSampleEvery)(body(run))
      finished <- Clock.nanoTime
      // Written once more: what is still queued at the end is counted in the next scenario otherwise.
      _ <- ZIO.sleep(Duration.fromMillis(config.queueSampleEvery.toMillis * 2))
      after <- AuthAntifraudMetrics.read(client, config.authMetricsUrl)
      sentRanges = stub.sentByRange.map((range, n) => range -> (n - stubBefore.getOrElse(range, 0L))).filter(_._2 > 0)
    yield ScenarioResult(
      scenario = scenario.wire,
      seconds = (finished - started) / 1e9,
      arrivals = outcome.summary.arrivals,
      waited = outcome.summary.waited,
      latencies = run.measurements.quantiles,
      otp = run.main.snapshot,
      legitInRange = outcome.legitInRange.map(_.snapshot),
      legitElsewhere = outcome.legitElsewhere.map(_.snapshot),
      failures = run.main.failures,
      smsSent = stub.sent - sentBefore,
      smsRanges = sentRanges.size,
      smsBusiestRange = if sentRanges.isEmpty then 0L else sentRanges.values.max,
      auth = after.minus(before).copy(queueSize = peak),
    )

case class ControlSetupFailed(sessions: Int, failures: Map[String, Long])
  extends RuntimeException(s"none of the $sessions logins that the token control starts from completed: ${failures.mkString(", ")}")
