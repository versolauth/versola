package versola.loadgen.antifraud

import versola.loadgen.protocol.{AuthClient, HttpAuthClient, Tokens}
import zio.http.{Client, Header, Headers}
import zio.{Clock, Duration, Queue, Task, UIO, ZIO}

import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Runs the scenarios of `role = antifraud` against one `auth`, and says what each did.
  *
  * Every scenario is an arrival process at a fixed rate (the sessions are started on a schedule, not when the
  * last one is done, so a slow system does not slow the offered load down) over fresh numbers.
  *
  * Several `auth` instances (`targets.auth-urls`) are given the sessions in turn, **a session staying on the instance
  * it started on**: its conversation, authorization code and tokens are in the database, so another instance could
  * continue it, but every call of one session going to one instance is what a deployment's balancer does with a
  * sticky cookie and what this measures. The antifraud state that is in memory per instance (the SMS budget, the
  * velocity counters between flushes) therefore sees a share of the traffic of each scenario.
  *
  * @param auths the client of each instance; a session with a source address uses it with the address in the header the
  *              stack reads it from (see [[SourcesConfig]])
  */
final class Scenarios(
    client: Client,
    auths: Vector[HttpAuthClient],
    config: AntifraudConfig,
    stub: SmsStub,
    numbers: PhoneNumbers,
    random: SplittableRandom,
    solver: PowSolver,
):

  private val turn = AtomicInteger(0)

  /** The next instance in turn, with the session's source address on its requests. */
  private def instance(source: Option[String]): AuthClient =
    val chosen = auths(Math.floorMod(turn.getAndIncrement(), auths.size))
    source.fold[AuthClient](chosen)(address => chosen.withHeaders(Headers(Header.Custom(config.sources.header, address))))

  /** The flows of one session: which instance it is on is decided here, so make them where the session starts. */
  private def flowsFor(measurements: Measurements, tally: Tally, measureOtp: Boolean, source: Option[String]): OtpFlows =
    OtpFlows(
      auth = instance(source),
      config = config,
      stub = stub,
      measurements = measurements,
      tally = tally,
      solver = solver,
      measureOtp = measureOtp,
    )

  private val sources = Sources(random)

  /** The addresses legitimate sessions come from, drawn once per run. */
  private val legitPool: Vector[String] = if config.sources.enabled then sources.pool(config.sources.legit) else Vector.empty

  /** The address of a session, or none when the run sends no source header. */
  private def fromPool(pool: Vector[String]): Option[String] =
    if pool.isEmpty then None else Some(pool(random.nextInt(pool.size)))

  /** One address for an attack that comes from one place, fresh for each scenario. */
  private def ownSource(): Option[String] = Option.when(config.sources.enabled)(sources.next())

  def run(scenario: AntifraudScenario): Task[ScenarioResult] =
    scenario match
      case AntifraudScenario.Normal => normal
      case AntifraudScenario.PrefixAttack => prefixAttack
      case AntifraudScenario.DeviceAttack => deviceAttack
      case AntifraudScenario.DistributedAttack => distributedAttack
      case AntifraudScenario.TokenControl => tokenControl
      case AntifraudScenario.MixedAttack => mixedAttack
      case AntifraudScenario.ConversionMimicking => conversionMimicking
      case AntifraudScenario.TargetedPhone => targetedPhone
      case AntifraudScenario.VerifyGuessing => verifyGuessing
      case AntifraudScenario.FakeTiming => fakeTiming

  /** Logins nobody measures, so that the first scenario does not pay for the JIT, the connections and the caches. */
  def warmup: Task[Unit] =
    val phones = List.fill(config.warmupSessions)(numbers.any())
    ZIO
      .foreachParDiscard(phones)(phone =>
        flowsFor(Measurements(), Tally(), measureOtp = false, source = None).login(phone).ignore,
      )
      .withParallelism(4)

  private case class Draw(phone: String, verifies: Boolean, refreshes: Int, inRange: Boolean, source: Option[String])

  private def legitDraw(inRangeShare: Double, range: Option[String]): UIO[Draw] =
    val settings = config.normal
    ZIO.succeed:
      val inRange = range.isDefined && random.nextDouble() < inRangeShare
      Draw(
        phone = range.filter(_ => inRange).fold(numbers.any())(numbers.in),
        verifies = random.nextDouble() < settings.conversion,
        refreshes = settings.refreshesMin + random.nextInt(settings.refreshesMax - settings.refreshesMin + 1),
        inRange = inRange,
        source = fromPool(legitPool),
      )

  /** A legitimate user: asks for a code, enters it unless they give up, and uses the tokens. */
  private def legitSession(run: Run, tally: Tally, draw: Draw): UIO[Unit] =
    val flows = run.flows(tally, source = draw.source)
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
      Arrivals
        .run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(legitDraw(0.0, None))(draw =>
          legitSession(run, run.main, draw),
        )
        .map(Outcome(_, None, None))

  /** Many codes to numbers of one range from one source, none verified. The range is drawn once per run, so that a
    * run made after another does not meet a range the first one closed (the budget remembers an hour). */
  private def prefixAttack: Task[ScenarioResult] =
    val range = numbers.anyRange()
    measured(AntifraudScenario.PrefixAttack): run =>
      val source = ownSource()
      Arrivals
        .run(rate = config.prefixAttack.rate, duration = config.prefixAttack.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed(numbers.in(range)),
        )(phone => run.flows(run.main, source = source).requestOtp(phone).ignore)
        .map(Outcome(_, None, None))

  /** One source asking for codes to numbers all over, none verified. */
  private def deviceAttack: Task[ScenarioResult] =
    measured(AntifraudScenario.DeviceAttack): run =>
      val source = ownSource()
      Arrivals
        .run(rate = config.deviceAttack.rate, duration = config.deviceAttack.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed(numbers.any()),
        )(phone => run.flows(run.main, source = source).requestOtp(phone).ignore)
        .map(Outcome(_, None, None))

  /** The prefix attack spread over `sources.distributed` addresses: each request from one of them, so that no
    * source is seen often. What is left to stop it is the range, and the numbers. */
  private def distributedAttack: Task[ScenarioResult] =
    val range = numbers.anyRange()
    val pool = if config.sources.enabled then sources.pool(config.sources.distributed) else Vector.empty
    measured(AntifraudScenario.DistributedAttack): run =>
      Arrivals
        .run(rate = config.distributedAttack.rate, duration = config.distributedAttack.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed((numbers.in(range), fromPool(pool))),
        )((phone, source) => run.flows(run.main, source = source).requestOtp(phone).ignore)
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
      val attackSource = ownSource()
      val attack = Arrivals.run(rate = settings.attackRate, duration = settings.duration, maxInFlight = config.maxInFlight)(
        ZIO.succeed(numbers.in(range)),
      )(phone => run.flows(run.main, source = attackSource).requestOtp(phone).ignore)
      val legit = Arrivals.run(rate = settings.normalRate, duration = settings.duration, maxInFlight = config.maxInFlight)(
        legitDraw(settings.legitInRangeShare, Some(range)),
      )(draw => if draw.inRange then legitSession(run, inRange, draw) else legitSession(run, elsewhere, draw))
      attack.zipPar(legit).map((a, l) =>
        Outcome(ArrivalSummary(arrivals = a.arrivals + l.arrivals, waited = a.waited + l.waited), Some(inRange), Some(elsewhere)),
      )

  /** An attack on one range whose sender enters the code of `verify-share` of the numbers, so that the range keeps
    * a conversion. */
  private def conversionMimicking: Task[ScenarioResult] =
    val settings = config.mimicking
    val range = numbers.anyRange()
    measured(AntifraudScenario.ConversionMimicking): run =>
      val source = ownSource()
      Arrivals
        .run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed((numbers.in(range), random.nextDouble() < settings.verifyShare)),
        ): (phone, verifies) =>
          val flows = run.flows(run.main, source = source)
          flows.requestOtp(phone).flatMap {
            case sent: OtpFlows.Requested.Sent if verifies => flows.verify(sent, phone).unit
            case _ => ZIO.unit
          }.ignore
        .map(Outcome(_, None, None))

  /** One number asked for codes again and again. */
  private def targetedPhone: Task[ScenarioResult] =
    val phone = numbers.any()
    measured(AntifraudScenario.TargetedPhone): run =>
      val source = ownSource()
      Arrivals
        .run(rate = config.targeted.rate, duration = config.targeted.duration, maxInFlight = config.maxInFlight)(ZIO.succeed(phone))(number =>
          run.flows(run.main, source = source).requestOtp(number).ignore,
        )
        .map(Outcome(_, None, None))

  /** A code requested for a new number and then `attempts` random codes entered. A guess that is taken counts as
    * `verified`. */
  private def verifyGuessing: Task[ScenarioResult] =
    val settings = config.guessing
    measured(AntifraudScenario.VerifyGuessing): run =>
      val source = ownSource()
      val digits = config.otp.length
      Arrivals
        .run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed((numbers.any(), List.fill(settings.attempts)(List.fill(digits)(random.nextInt(10)).mkString))),
        ): (phone, guesses) =>
          val flows = run.flows(run.main, source = source)
          flows.requestOtp(phone).flatMap {
            case sent: OtpFlows.Requested.Sent => ZIO.foreachDiscard(guesses)(guess => flows.enter(sent, guess))
            case _ => ZIO.unit
          }.ignore
        .map(Outcome(_, None, None))

  /** `/token` and refresh alone: sessions are made first (only their code exchange is measured), then refreshed at
    * a fixed rate. A session is refreshed by one fiber at a time, since a token used twice is a reuse, and by the
    * instance it was made on. */
  private def tokenControl: Task[ScenarioResult] =
    val settings = config.tokenControl
    measured(AntifraudScenario.TokenControl): run =>
      for
        pool <- Queue.unbounded[(Tokens, OtpFlows)]
        phones = List.fill(settings.sessions)(numbers.any())
        _ <- ZIO
          .foreachParDiscard(phones): phone =>
            val flows = run.flows(run.main, measureOtp = false)
            flows.login(phone).flatMap(ZIO.foreachDiscard(_)(tokens => pool.offer((tokens, flows)))).ignore
          .withParallelism(8)
        made <- pool.size
        _ <- ZIO.fail(ControlSetupFailed(settings.sessions, run.main.failures)).when(made == 0)
        summary <- Arrivals.run(rate = settings.rate, duration = settings.duration, maxInFlight = config.maxInFlight)(pool.take): (tokens, flows) =>
          flows.refresh(tokens).flatMap(refreshed => pool.offer((refreshed, flows))).ignore
      yield Outcome(summary, None, None)

  /** `requests` requests for a code to numbers of a `fake` list entry and as many to real ones, alternating in a random
    * order and each from a source of its own (so that no limit on a source touches them), timed on the call that makes
    * `auth` decide. The numbers that were asked for a code and whose code did reach the SMS stub are told apart from those
    * that did not: a fake number must not be sent one (the entry is missing, or does not match, if it was), and a real one
    * must (or the stub is not what `auth` sends to). Judged by [[FakeTimingCheck]]. */
  private def fakeTiming: Task[ScenarioResult] =
    val settings = config.fakeTiming
    val dial = config.numbers.dialCode.filter(_.isDigit)
    val prefix = settings.fakePrefix.map(_.filter(_.isDigit))
    def isFake(phone: String): Boolean =
      if settings.fakeNumbers.nonEmpty then settings.fakeNumbers.map(_.filter(_.isDigit)).contains(phone.filter(_.isDigit))
      else prefix.exists(phone.filter(_.isDigit).startsWith)
    def fakeNumber(index: Int): String =
      if settings.fakeNumbers.nonEmpty then settings.fakeNumbers(index % settings.fakeNumbers.size)
      else numbers.in(dial + prefix.getOrElse("").drop(dial.length))
    def realNumber(): String =
      var phone = numbers.any()
      while isFake(phone) do phone = numbers.any()
      phone
    measured(AntifraudScenario.FakeTiming): run =>
      val samples = Map(true -> Measurements(), false -> Measurements())
      val tallies = Map(true -> Tally(), false -> Tally())
      val sentPhones = Map(true -> java.util.concurrent.ConcurrentLinkedQueue[String](), false -> java.util.concurrent.ConcurrentLinkedQueue[String]())
      val probes: Vector[(Boolean, String, Option[String])] = Vector.tabulate(settings.requests): index =>
        val pair = Vector((true, fakeNumber(index), ownSource()), (false, realNumber(), ownSource()))
        if random.nextBoolean() then pair.reverse else pair
      .flatten
      val probeIndex = AtomicInteger(0)
      val duration = Duration.fromMillis(math.max(1L, math.round(probes.size / settings.rate * 1000.0)))
      for
        // `auth` makes a fake send wait as long as the real ones have been taking: it has to have seen some.
        _ <- ZIO
          .foreachParDiscard(List.fill(settings.warmup)(realNumber())): phone =>
            flowsFor(Measurements(), Tally(), measureOtp = false, source = ownSource()).requestOtp(phone).ignore
          .withParallelism(4)
        summary <- Arrivals.run(rate = settings.rate, duration = duration, maxInFlight = config.maxInFlight)(
          ZIO.succeed(probes(math.min(probeIndex.getAndIncrement(), probes.size - 1))),
        ): (fake, phone, source) =>
          flowsFor(Measurements(), tallies(fake), measureOtp = false, source = source)
            .requestOtp(phone)
            .flatMap:
              case sent: OtpFlows.Requested.Sent =>
                ZIO.succeed {
                  samples(fake).record("request", sent.requestMicros)
                  sentPhones(fake).add(phone)
                }
              case _ => ZIO.unit
            .ignore
        group = (fake: Boolean) =>
          val tally = tallies(fake).snapshot
          val timing = samples(fake)
          val ps = timing.percentiles("request", List(50.0, 90.0, 99.0)).getOrElse(List(0.0, 0.0, 0.0))
          TimingGroup(
            requests = tally.requested,
            sent = tally.sent,
            refused = tally.refused,
            other = tally.other,
            reachedSms = sentPhones(fake).asScala.count(stub.codeFor(_).isDefined).toLong,
            p50Ms = ps(0),
            p90Ms = ps(1),
            p99Ms = ps(2),
            maxMs = timing.max("request"),
          )
        _ = List(true, false).foreach(fake => run.main.absorb(tallies(fake)))
      yield Outcome(summary, None, None, Some(FakeTimingCheck.judge(fake = group(true), real = group(false), toleranceMs = settings.tolerance.toMillis.toDouble, minSamples = FakeTimingCheck.MinSamples)))

  /** What a scenario's body is given: the measurements it records into, the tally of its own requests, and a way to
    * make flows that count into another tally. A session's flows are made where it starts: that picks its instance. */
  private final class Run(val measurements: Measurements, val main: Tally):
    def flows(tally: Tally, measureOtp: Boolean = true, source: Option[String] = None): OtpFlows =
      flowsFor(measurements, tally, measureOtp, source)

  private case class Outcome(
      summary: ArrivalSummary,
      legitInRange: Option[Tally],
      legitElsewhere: Option[Tally],
      fakeTiming: Option[FakeTimingResult] = None,
  )

  private def measured(scenario: AntifraudScenario)(body: Run => Task[Outcome]): Task[ScenarioResult] =
    val run = Run(Measurements(), Tally())
    val urls = config.metricsUrls
    for
      before <- AuthAntifraudMetrics.readAll(client, urls)
      stubBefore = stub.sentByRange
      sentBefore = stub.sent
      started <- Clock.nanoTime
      (outcome, peak) <- QueueSampler.around(client = client, urls = urls, every = config.queueSampleEvery)(body(run))
      finished <- Clock.nanoTime
      // Written once more: what is still queued at the end is counted in the next scenario otherwise.
      _ <- ZIO.sleep(Duration.fromMillis(config.queueSampleEvery.toMillis * 2))
      after <- AuthAntifraudMetrics.readAll(client, urls)
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
      challenge = run.measurements.challenge.snapshot,
      fakeTiming = outcome.fakeTiming,
    )

case class ControlSetupFailed(sessions: Int, failures: Map[String, Long])
  extends RuntimeException(s"none of the $sessions logins that the token control starts from completed: ${failures.mkString(", ")}")
