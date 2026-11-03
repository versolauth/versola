package versola.loadgen.antifraud

import versola.loadgen.config.LoadgenConfig
import versola.loadgen.protocol.{ClientCreds, ClientRegistration, ClientRegistry, HttpAuthClient, LoadgenHttpClient}
import zio.*
import zio.http.{Client, Header, Headers, Request}
import zio.json.*

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.SplittableRandom

/** `role = antifraud`: the phone sign-in driven with and without antifraud in `auth` (#771, P0-7).
  *
  * Run once against an `auth` configured one way, it writes its report; run again against the same `auth`
  * configured another way with `antifraud.baseline-path` naming the first report, it also says what the second
  * configuration added. See `loadgen/antifraud/README.md` for the commands.
  */
object AntifraudRun:

  def run(config: LoadgenConfig): RIO[Client & Scope, Unit] =
    for
      settings <- ZIO.fromOption(config.antifraud).orElseFail(MissingAntifraudConfig)
      _ <- validate(settings, config.targets.allAuthUrls)
      client <- ZIO.service[Client]
      stub = SmsStub(codeLength = settings.otp.length, rangeLength = settings.numbers.rangeLength, responseDelay = settings.smsStub.responseDelay)
      _ <- SmsStub.serve(stub, settings.smsStub)
      _ <- awaitStub(client, settings)
      registry = ClientRegistry(
        byId = Map(settings.clientId -> ClientRegistration(ClientCreds(settings.clientId, None), settings.redirectUri)),
        defaultClientId = settings.clientId,
      )
      urls = config.targets.allAuthUrls
      auths <- ZIO
        .foreach(urls.toVector): url =>
          HttpAuthClient.make(
            client = client,
            targets = config.targets.copy(authUrl = url),
            clients = registry,
            requestTimeout = LoadgenHttpClient.requestTimeout,
          )
        .mapError(error => InvalidAntifraudConfig(error.toString))
      solver <- PowSolver.make(settings.challenge.solverThreads)
      seed <- ZIO.fromOption(settings.seed).orElse(Random.nextLong)
      random = SplittableRandom(seed)
      scenarios = Scenarios(
        client = client,
        auths = auths,
        config = settings,
        stub = stub,
        numbers = PhoneNumbers(settings.numbers, random),
        random = random,
        solver = solver,
      )
      _ <- AuthAntifraudMetrics.readAll(client, settings.metricsUrls).mapError(error =>
        InvalidAntifraudConfig(s"auth's metrics at ${settings.metricsUrls.mkString(", ")} are not all readable: $error"),
      )
      _ <- warnIfPlainOutgrowsSessions(settings)
      _ <- ZIO.logInfo(s"antifraud loadgen: ${urls.size} auth instance(s) ${urls.mkString(", ")}, ${settings.metricsUrls.size} metrics url(s), client ${settings.otp.client.wire}")
      _ <- ZIO.logInfo(s"antifraud loadgen '${settings.label}': warming up with ${settings.warmupSessions} logins (seed $seed)")
      _ <- scenarios.warmup
      started <- Clock.instant
      results <- ZIO.foreach(settings.selected): scenario =>
        ZIO.logInfo(s"scenario ${scenario.wire}") *> scenarios.run(scenario).tap(result =>
          ZIO.logInfo(s"scenario ${scenario.wire} done: ${result.otp}"),
        )
      report = AntifraudReport(
        label = settings.label,
        startedAt = started.toString,
        scenarios = results,
        client = settings.otp.client.wire,
        instances = urls,
      )
      baseline <- ZIO.foreach(settings.baselinePath)(readReport)
      comparisons = Comparison.compare(
        report = report,
        baseline = baseline,
        ceilingMs = settings.addedLatencyCeiling.toMillis.toDouble,
        toleranceMs = settings.controlTolerance.toMillis.toDouble,
      )
      checks = Checks.of(report = report, attackSmsCeiling = settings.attackSmsCeiling, legitRefusalCeiling = settings.legitRefusalCeiling)
      text = ReportText.render(
        report = report,
        comparisons = comparisons,
        checks = checks,
        ceilingMs = settings.addedLatencyCeiling.toMillis.toDouble,
        toleranceMs = settings.controlTolerance.toMillis.toDouble,
      )
      _ <- write(Path.of(settings.reportPath), report.toJsonPretty)
      _ <- write(Path.of(settings.reportPath.stripSuffix(".json") + ".md"), text)
      _ <- Console.printLine(text)
      failed = comparisons.filter(_.verdict == Verdict.Fail).map(c => s"${c.scenario}/${c.call} added p99") ++
        checks.filter(_.verdict == Verdict.Fail).map(c => s"${c.scenario}: ${c.name}")
      _ <- ZIO.fail(AntifraudAcceptanceFailed(failed)).when(failed.nonEmpty)
    yield ()

  private def validate(settings: AntifraudConfig, authUrls: List[String]): IO[InvalidAntifraudConfig, Unit] =
    val problems = List(
      Option.when(settings.normal.refreshesMin > settings.normal.refreshesMax)("normal.refreshes-min is above refreshes-max"),
      Option.when(settings.normal.refreshesMin < 0)("normal.refreshes-min is negative"),
      Option.when(settings.normal.conversion < 0.0 || settings.normal.conversion > 1.0)("normal.conversion is not between 0 and 1"),
      Option.when(settings.numbers.rangeLength <= settings.numbers.dialCode.count(_.isDigit) + settings.numbers.leads.map(_.length).max)(
        "numbers.range-length leaves no digits to tell ranges apart: it has to be longer than the country code and a lead",
      ),
      Option.when(settings.attackSmsCeiling < 0.0 || settings.attackSmsCeiling > 1.0)("attack-sms-ceiling is not between 0 and 1"),
      Option.when(settings.legitRefusalCeiling < 0.0 || settings.legitRefusalCeiling > 1.0)("legit-refusal-ceiling is not between 0 and 1"),
      Option.when(
        settings.mixed.legitInRangeShare < 0.0 || settings.mixed.legitInRangeShare > 1.0,
      )("mixed.legit-in-range-share is not between 0 and 1"),
      Option.when(settings.mimicking.verifyShare < 0.0 || settings.mimicking.verifyShare > 1.0)("mimicking.verify-share is not between 0 and 1"),
      Option.when(settings.maxInFlight <= 0)("max-in-flight must be positive"),
      Option.when(
        List(
          settings.normal.rate,
          settings.prefixAttack.rate,
          settings.deviceAttack.rate,
          settings.distributedAttack.rate,
          settings.tokenControl.rate,
        )
          .exists(rate => !(rate > 0.0)),
      )(
        "every rate must be positive",
      ),
      Option.when(settings.authMetricsUrls.nonEmpty && settings.authMetricsUrls.size != authUrls.size)(
        s"auth-metrics-urls has ${settings.authMetricsUrls.size} urls and the targets name ${authUrls.size} auth instances: one diagnostics url for each, or the sums are of some of them",
      ),
      Option.when(settings.challenge.maxRounds < 0 || settings.challenge.maxAsks <= 0 || settings.challenge.maxDifficulty < 0)(
        "challenge.max-rounds, max-asks and max-difficulty must not be negative (max-asks positive)",
      ),
      Option.when(settings.selected.contains(AntifraudScenario.FakeTiming)) {
        val timing = settings.fakeTiming
        val dial = settings.numbers.dialCode.filter(_.isDigit)
        if settings.otp.source != OtpSource.Stub then "fake-timing needs otp.source = stub: a fake number is told from a real one by what reaches the SMS stub"
        else if timing.fakePrefix.isEmpty && timing.fakeNumbers.isEmpty then "fake-timing needs fake-timing.fake-prefix or fake-timing.fake-numbers: the numbers of the fake list entry"
        else if timing.fakePrefix.exists(p => !p.filter(_.isDigit).startsWith(dial)) then s"fake-timing.fake-prefix has to start with the country code $dial"
        else if timing.requests <= 0 || !(timing.rate > 0.0) || timing.warmup < 0 then "fake-timing.requests and rate must be positive"
        else ""
      }.filter(_.nonEmpty),
      Option.when(settings.sources.enabled && (settings.sources.legit <= 0 || settings.sources.distributed <= 0))(
        "sources.legit and sources.distributed must be positive when a source header is sent",
      ),
    ).flatten
    ZIO.fail(InvalidAntifraudConfig(problems.mkString("; "))).when(problems.nonEmpty).unit

  /** A plain client holds each session for the pause; more of them than `max-in-flight` means the arrivals wait for a slot and
    * the run is the loadgen's, not the offered load. */
  private def warnIfPlainOutgrowsSessions(settings: AntifraudConfig): UIO[Unit] =
    val held = (settings.challenge.pause.toMillis + settings.challenge.pauseSlack.toMillis) / 1000.0
    def rate(scenario: AntifraudScenario): Double = scenario match
      case AntifraudScenario.Normal => settings.normal.rate
      case AntifraudScenario.TokenControl => 0.0
      case AntifraudScenario.PrefixAttack => settings.prefixAttack.rate
      case AntifraudScenario.DeviceAttack => settings.deviceAttack.rate
      case AntifraudScenario.DistributedAttack => settings.distributedAttack.rate
      case AntifraudScenario.MixedAttack => settings.mixed.normalRate + settings.mixed.attackRate
      case AntifraudScenario.ConversionMimicking => settings.mimicking.rate
      case AntifraudScenario.TargetedPhone => settings.targeted.rate
      case AntifraudScenario.VerifyGuessing => settings.guessing.rate
      case AntifraudScenario.FakeTiming => settings.fakeTiming.rate
    val most = settings.selected.map(rate).maxOption.getOrElse(0.0)
    ZIO
      .logWarning(
        f"otp.client = plain holds a session for the pause: at $most%.0f a second and ${held}%.0f s that is up to ${most * held}%.0f sessions at once, over max-in-flight ${settings.maxInFlight}: arrivals will wait (`waited`). Raise antifraud.max-in-flight (AF_MAX_IN_FLIGHT).",
      )
      .when(settings.otp.client == OtpClient.Plain && most * held > settings.maxInFlight)
      .unit

  /** The stub is forked, and a scenario that started before it listened would count refused sends as the system's. */
  private def awaitStub(client: Client, settings: AntifraudConfig): Task[Unit] =
    val probe = ZIO.scoped(client.request(Request.get(s"http://localhost:${settings.smsStub.port}/sms/stats")).flatMap(_.body.asString)).unit
    probe.retry(Schedule.spaced(200.millis) && Schedule.recurs(50))

  private def readReport(path: String): Task[AntifraudReport] =
    ZIO
      .attemptBlocking(Files.readString(Path.of(path)))
      .flatMap(text =>
        ZIO.fromEither(text.fromJson[AntifraudReport]).mapError(error => InvalidAntifraudConfig(s"baseline $path is not a report: $error")),
      )

  private def write(path: Path, text: String): Task[Unit] =
    ZIO.attemptBlocking:
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.writeString(path, text)
    .unit

case object MissingAntifraudConfig extends RuntimeException("role = antifraud requires an 'antifraud' configuration block")

case class InvalidAntifraudConfig(reason: String) extends RuntimeException(reason)

case class AntifraudAcceptanceFailed(calls: List[String])
  extends RuntimeException(s"added p99 is over its limit for: ${calls.mkString(", ")}")
