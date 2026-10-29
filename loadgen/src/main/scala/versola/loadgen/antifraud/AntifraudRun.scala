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
      _ <- validate(settings)
      client <- ZIO.service[Client]
      stub = SmsStub(codeLength = settings.otp.length, rangeLength = settings.numbers.rangeLength, responseDelay = settings.smsStub.responseDelay)
      _ <- SmsStub.serve(stub, settings.smsStub)
      _ <- awaitStub(client, settings)
      registry = ClientRegistry(
        byId = Map(settings.clientId -> ClientRegistration(ClientCreds(settings.clientId, None), settings.redirectUri)),
        defaultClientId = settings.clientId,
      )
      auth <- HttpAuthClient
        .make(client = client, targets = config.targets, clients = registry, requestTimeout = LoadgenHttpClient.requestTimeout)
        .mapError(error => InvalidAntifraudConfig(error.toString))
      seed <- ZIO.fromOption(settings.seed).orElse(Random.nextLong)
      random = SplittableRandom(seed)
      scenarios = Scenarios(
        client = client,
        auth = auth,
        sourced = address => auth.withHeaders(Headers(Header.Custom(settings.sources.header, address))),
        config = settings,
        stub = stub,
        numbers = PhoneNumbers(settings.numbers, random),
        random = random,
      )
      _ <- AuthAntifraudMetrics.read(client, settings.authMetricsUrl).mapError(error =>
        InvalidAntifraudConfig(s"auth's metrics at ${settings.authMetricsUrl} are not readable: $error"),
      )
      _ <- ZIO.logInfo(s"antifraud loadgen '${settings.label}': warming up with ${settings.warmupSessions} logins (seed $seed)")
      _ <- scenarios.warmup
      started <- Clock.instant
      results <- ZIO.foreach(settings.selected): scenario =>
        ZIO.logInfo(s"scenario ${scenario.wire}") *> scenarios.run(scenario).tap(result =>
          ZIO.logInfo(s"scenario ${scenario.wire} done: ${result.otp}"),
        )
      report = AntifraudReport(label = settings.label, startedAt = started.toString, scenarios = results)
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

  private def validate(settings: AntifraudConfig): IO[InvalidAntifraudConfig, Unit] =
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
      Option.when(settings.sources.enabled && (settings.sources.legit <= 0 || settings.sources.distributed <= 0))(
        "sources.legit and sources.distributed must be positive when a source header is sent",
      ),
    ).flatten
    ZIO.fail(InvalidAntifraudConfig(problems.mkString("; "))).when(problems.nonEmpty).unit

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
