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

  /** Logins nobody measures, so that the first scenario does not pay for the JIT, the connections and the caches. */
  def warmup: Task[Unit] =
    val flows = OtpFlows(auth = auth, config = config, stub = stub, measurements = Measurements(), tally = Tally(), measureOtp = false)
    val phones = List.fill(config.warmupSessions)(numbers.any())
    ZIO.foreachParDiscard(phones)(phone => flows.login(phone).ignore).withParallelism(4)

  private case class Draw(phone: String, verifies: Boolean, refreshes: Int)

  private def normal: Task[ScenarioResult] =
    val settings = config.normal
    measured(AntifraudScenario.Normal): (flows, tally) =>
      val next = ZIO.succeed:
        Draw(
          phone = numbers.any(),
          verifies = random.nextDouble() < settings.conversion,
          refreshes = settings.refreshesMin + random.nextInt(settings.refreshesMax - settings.refreshesMin + 1),
        )
      Arrivals.run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(next): draw =>
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

  /** Many codes to numbers of one range, none verified. The range is drawn once per run, so that a run made after
    * another does not meet a range the first one closed (the budget remembers an hour). */
  private def prefixAttack: Task[ScenarioResult] =
    val range = numbers.anyRange()
    measured(AntifraudScenario.PrefixAttack): (flows, _) =>
      Arrivals.run(
        rate = config.prefixAttack.rate,
        duration = config.prefixAttack.duration,
        maxInFlight = config.maxInFlight,
      )(ZIO.succeed(numbers.in(range)))(phone =>
        flows.requestOtp(phone).ignore,
      )

  /** One source asking for codes to numbers all over, none verified. */
  private def deviceAttack: Task[ScenarioResult] =
    measured(AntifraudScenario.DeviceAttack): (flows, _) =>
      Arrivals.run(
        rate = config.deviceAttack.rate,
        duration = config.deviceAttack.duration,
        maxInFlight = config.maxInFlight,
      )(ZIO.succeed(numbers.any()))(phone =>
        flows.requestOtp(phone).ignore,
      )

  /** `/token` and refresh alone: sessions are made first (only their code exchange is measured), then refreshed at
    * a fixed rate. A session is refreshed by one fiber at a time, since a token used twice is a reuse. */
  private def tokenControl: Task[ScenarioResult] =
    val settings = config.tokenControl
    measured(AntifraudScenario.TokenControl): (flows, tally) =>
      for
        pool <- Queue.unbounded[Tokens]
        phones = List.fill(settings.sessions)(numbers.any())
        _ <- ZIO.foreachParDiscard(phones)(phone => flows.login(phone).flatMap(ZIO.foreachDiscard(_)(pool.offer)).ignore).withParallelism(8)
        made <- pool.size
        _ <- ZIO.fail(ControlSetupFailed(settings.sessions, tally.failures)).when(made == 0)
        summary <- Arrivals.run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(pool.take): tokens =>
          flows.refresh(tokens).flatMap(pool.offer).ignore
      yield summary

  private def measured(scenario: AntifraudScenario)(body: (OtpFlows, Tally) => Task[ArrivalSummary]): Task[ScenarioResult] =
    val measurements = Measurements()
    val tally = Tally()
    val flows = OtpFlows(
      auth = auth,
      config = config,
      stub = stub,
      measurements = measurements,
      tally = tally,
      measureOtp = scenario != AntifraudScenario.TokenControl,
    )
    for
      before <- AuthAntifraudMetrics.read(client, config.authMetricsUrl)
      stubBefore = stub.sentByRange
      sentBefore = stub.sent
      started <- Clock.nanoTime
      (summary, peak) <- QueueSampler.around(client = client, url = config.authMetricsUrl, every = config.queueSampleEvery)(body(flows, tally))
      finished <- Clock.nanoTime
      // Written once more: what is still queued at the end is counted in the next scenario otherwise.
      _ <- ZIO.sleep(Duration.fromMillis(config.queueSampleEvery.toMillis * 2))
      after <- AuthAntifraudMetrics.read(client, config.authMetricsUrl)
      sentRanges = stub.sentByRange.map((range, n) => range -> (n - stubBefore.getOrElse(range, 0L))).filter(_._2 > 0)
    yield ScenarioResult(
      scenario = scenario.wire,
      seconds = (finished - started) / 1e9,
      arrivals = summary.arrivals,
      waited = summary.waited,
      latencies = measurements.quantiles,
      otp = tally.snapshot,
      failures = tally.failures,
      smsSent = stub.sent - sentBefore,
      smsRanges = sentRanges.size,
      smsBusiestRange = if sentRanges.isEmpty then 0L else sentRanges.values.max,
      auth = after.minus(before).copy(queueSize = peak),
    )

case class ControlSetupFailed(sessions: Int, failures: Map[String, Long])
  extends RuntimeException(s"none of the $sessions logins that the token control starts from completed: ${failures.mkString(", ")}")
