package versola.loadgen.driver

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.loadgen.config.{ClientsConfig, LoadgenConfig, ShardConfig}
import versola.loadgen.coordinator.{LoadPlan, PlanScenario}
import versola.loadgen.metrics.{DriverHealthReporter, DriverHealthSource, LatencyRecorder, ProcessCpu}
import versola.loadgen.protocol.*
import versola.loadgen.scenario.*
import versola.loadgen.scheduler.*
import versola.loadgen.store.*
import versola.util.postgres.PostgresHikariDataSource
import zio.*
import zio.http.Client

import java.time.Instant

/** `role = driver`: the half of the emulator that generates load (dev spec §7).
  *
  * The process is three things layered on each other:
  *
  *   - **boot**, once: the store pool, the protocol clients, the recorders, the user pool. None
  *     of it depends on the plan, so none of it is rebuilt when the plan moves.
  *   - **the poll loop**, every `coordinator.poll-interval`: fetch `GET /plan`, post
  *     `POST /drivers/report`, and decide what the driver should be doing ([[DriverCommand]]).
  *   - **a generation**, for as long as the plan's anchor and shard map hold: one [[DriverLoop]]
  *     running §7.2's arrival process against §7.4's session state machine.
  *
  * A driver keeps running on the last plan it fetched (§12), so a coordinator that is unreachable
  * costs freshness and nothing else -- a failed poll is logged and the current generation carries
  * on. The one failure the poll loop does not absorb is the loop's own: a campaign that has
  * latched an abort (§4's `Misconfigured`) is measuring something other than what it claims, and
  * a driver that kept polling through it would be a pod that looks alive and generates nothing.
  *
  * **What this does not schedule.** `PlanScenario.Registration` has no driver-side flow: the
  * scenario engine logs existing users in, and creating them is `loadgen seed`'s business. A plan
  * that publishes a registration rate is reported once rather than silently absorbed -- the ramp
  * would otherwise look like it was failing when nothing is driving it.
  */
object Driver:

  /** How far ahead of the clock the arrival generator may run, as [[DriverLoop]] bounds it. */
  val queueCapacity: Int = 1024

  /** How often §11's health gauges are published. Faster than the report interval because these
    * are a dashboard's series: a gauge written every 10 s and scraped every 15 s shows the same
    * value twice as often as it changes.
    */
  val healthInterval: Duration = 5.seconds

  def run(config: LoadgenConfig): ZIO[Scope & ConfigProvider, Throwable, Unit] =
    boot(config).provideSome[Scope & ConfigProvider](LoadgenHttpClient.live)

  private def boot(config: LoadgenConfig): ZIO[Scope & ConfigProvider & Client, Throwable, Unit] =
    for
      shard <- ZIO.fromOption(config.shard).orElseFail(MissingDriverConfig("shard"))
      clients <- ZIO.fromOption(config.clients).orElseFail(MissingDriverConfig("clients"))
      actions <- ZIO.fromEither(BusinessActions.from(config.actions)).mapError(InvalidDriverConfig(_))
      // Derived once at boot and shared by every session fiber: the pool is a pure function of
      // the seed, so building it per generation would produce the same keys at the cost of a
      // keygen burst on every re-shard.
      dpop <- ZIO.foreach(config.dpop): settings =>
        DpopKeyPool
          .derive(settings.keySeed, settings.keyPoolSize)
          .mapError(error => InvalidDriverConfig(error.toString))
      _ <- ZIO.foreachDiscard(dpop): pool =>
        ZIO.logInfo(s"Driving with RFC 9449 DPoP: ${pool.size} client keys shared across the fleet")
      xa <- storeTransactor
      users = PostgresVirtualUserRepository(xa)
      sessions = PostgresDeviceSessionRepository(xa)
      events = PostgresEventRepository(xa)
      snapshots = PostgresMetricSnapshotRepository(xa)
      buffer <- WriteBehindBuffer.make(
        config.store.writeBehind,
        WriteBehindBuffer.capacityFor(config.store.writeBehind),
        PostgresDeferredWriteSink(users, sessions, events),
      )
      // Before anything is scheduled, and not as a background sweep: a session a previous
      // incarnation of this driver left mid-rotation is only dangerous while something might
      // still pick it up.
      _ <- SessionRecovery.retireInterruptedRotations(sessions, shard)
      latencies <- LatencyRecorder.make
      recorder <- ScenarioRecorder.make(latencies, buffer, ScenarioRecorder.eventSampleRate)
      client <- ZIO.service[Client]
      flows <- protocolFlows(config, clients, recorder, client)
      pool <- UserPool.paged(users, shard, UserPool.pageSize)
      busy <- BusyUsers.make
      lag <- ScheduleLag.make
      lagQuantile <- ScheduleLagQuantile.make
      tally <- ArrivalTally.make
      cpu <- ProcessCpu.make
      driverId = identityOf(shard)
      planClient <- PlanClient.make(client, config.coordinator.url, PlanClient.requestTimeout)
      initial <- firstPlan(planClient, config.coordinator.pollInterval)
      planRef <- Ref.make(initial)
      reporter = DriverReporter(
        campaign = config.campaign.name,
        driverId = driverId,
        shardIndex = shard.index,
        client = planClient,
        tally = tally,
        recorder = recorder,
        latencies = latencies,
        busy = busy,
        buffer = buffer,
        lagQuantile = lagQuantile,
        cpu = cpu,
      )
      _ <- SnapshotPublisher(config.campaign.name, driverId, latencies, snapshots).run(SnapshotPublisher.interval)
      _ <- lagQuantile.run(lag, ScheduleLagQuantile.sampleInterval)
      _ <- healthReporter(lag, busy, buffer).flatMap(_.run(healthInterval).forkScoped)
      _ <- ZIO.logInfo(
        s"Driver $driverId ready for campaign '${config.campaign.name}' on shard ${shard.index}; " +
          s"polling ${config.coordinator.url} every ${config.coordinator.pollInterval.render}",
      )
      engine = Engine(config, shard, clients, actions, flows, sessions, buffer, pool, busy, recorder, lag, tally, planRef, dpop)
      _ <- supervise(config, planClient, planRef, reporter, engine)
    yield ()

  /** Everything a generation is built from that boot already settled. Grouped so the supervisor's
    * signature states what it does rather than what it carries.
    */
  private final case class Engine(
      config: LoadgenConfig,
      shard: ShardConfig,
      clients: ClientsConfig,
      actions: BusinessActions,
      flows: ProtocolFlows,
      sessions: DeviceSessionRepository,
      buffer: WriteBehindBuffer,
      pool: UserPool,
      busy: BusyUsers,
      recorder: ScenarioRecorder,
      lag: ScheduleLag,
      tally: ArrivalTally,
      plan: Ref[LoadPlan],
      dpop: Option[DpopKeyPool],
  )

  private final case class ProtocolFlows(mobile: MobileFlows, web: WebFlows)

  /** One running generation and the fiber it is running on. */
  private final case class Running(generation: RunGeneration, fiber: Fiber.Runtime[Throwable, Unit])

  /** The poll loop, until the campaign is stopped or the running loop fails.
    *
    * The loop's failure is raced rather than polled for: an aborted campaign has to take the
    * process down at the instant it is latched, not at the end of the current poll interval,
    * because everything generated in between is load nobody will be able to interpret.
    */
  private def supervise(
      config: LoadgenConfig,
      planClient: PlanClient,
      planRef: Ref[LoadPlan],
      reporter: DriverReporter,
      engine: Engine,
  ): ZIO[Scope, Throwable, Unit] =
    for
      running <- Ref.make(Option.empty[Running])
      failed <- Promise.make[Throwable, Nothing]
      _ <- poll(config, planClient, planRef, reporter, engine, running, failed).raceFirst(failed.await)
      _ <- running.get.flatMap(current => ZIO.foreachDiscard(current)(_.fiber.interrupt))
    yield ()

  private def poll(
      config: LoadgenConfig,
      planClient: PlanClient,
      planRef: Ref[LoadPlan],
      reporter: DriverReporter,
      engine: Engine,
      running: Ref[Option[Running]],
      failed: Promise[Throwable, Nothing],
  ): ZIO[Scope, Throwable, Unit] =
    val tick =
      for
        fetched <- planClient.fetch.asSome
          .catchAllCause: cause =>
            ZIO.logWarningCause("Plan poll failed; continuing on the last plan fetched", cause).as(None)
        _ <- ZIO.foreachDiscard(fetched)(planRef.set)
        plan <- planRef.get
        _ <- reporter.post(plan.shards.epoch)
        stop <- react(DriverCommand.of(plan), plan, engine, running, failed)
      yield stop

    tick
      .flatMap: stop =>
        if stop then ZIO.logInfo("Campaign stopped; the driver is done").as(false)
        else ZIO.sleep(config.coordinator.pollInterval).as(true)
      .repeatWhile(identity)
      .unit

  /** @return whether the campaign is over, which is the poll loop's only exit. */
  private def react(
      command: DriverCommand,
      plan: LoadPlan,
      engine: Engine,
      running: Ref[Option[Running]],
      failed: Promise[Throwable, Nothing],
  ): ZIO[Scope, Throwable, Boolean] =
    command match
      case DriverCommand.Stop => halt(running, "the campaign was stopped").as(true)
      case DriverCommand.Idle => halt(running, s"the campaign is ${plan.state.label}").as(false)
      case DriverCommand.Run(generation) =>
        running.get.flatMap:
          case Some(current) if !DriverCommand.restartRequired(current.generation, generation) => ZIO.succeed(false)
          case Some(current) =>
            current.fiber.interrupt *>
              ZIO.logInfo(s"Plan moved from ${current.generation} to $generation; restarting the loop") *>
              startGeneration(generation, plan, engine, running, failed).as(false)
          case None => startGeneration(generation, plan, engine, running, failed).as(false)

  private def halt(running: Ref[Option[Running]], why: String): UIO[Unit] =
    running.getAndSet(None).flatMap:
      case None => ZIO.unit
      case Some(current) => current.fiber.interrupt *> ZIO.logInfo(s"Stopped generating load: $why")

  /** Builds and forks one generation's [[DriverLoop]].
    *
    * A fleet rate of zero is not scheduled at all. `ArrivalProcess.next` requires a positive
    * ceiling and dies on anything else, so a phase the campaign configured at `scale = 0` would
    * otherwise take the driver down rather than generate the nothing it asks for.
    */
  private def startGeneration(
      generation: RunGeneration,
      plan: LoadPlan,
      engine: Engine,
      running: Ref[Option[Running]],
      failed: Promise[Throwable, Nothing],
  ): ZIO[Scope, Throwable, Unit] =
    val anchor = Instant.ofEpochMilli(generation.startedAtEpochMillis)
    val fleetRate = sessionRateOf(plan)
    for
      _ <- warnAboutRegistration(plan)
      schedule <- ZIO
        .fromEither(CampaignSchedule.from(engine.config.campaign, anchor))
        .mapError(InvalidDriverConfig(_))
      shardRate = ArrivalProcess.shardRate(fleetRate, generation.shardCount)
      _ <- ZIO
        .logWarning("The plan publishes no session arrivals; generating nothing until it does")
        .when(shardRate <= 0.0)
      _ <- ZIO.when(shardRate > 0.0):
        val random = RandomSource.seeded(seedOf(engine.config.campaign.name, engine.shard))
        val shardOfGeneration = ShardConfig(engine.shard.index, generation.shardCount)
        val runner = SessionRunner(
          mobile = engine.flows.mobile,
          web = engine.flows.web,
          sessions = engine.sessions,
          buffer = engine.buffer,
          actions = engine.actions,
          ids = SessionIds.make(shardOfGeneration),
          thinkTime = ThinkTimeTable.build(engine.config.session.thinkTime, random.split()),
          clients = scenarioClientsOf(engine.clients),
          config = engine.config.session,
          dpop = engine.dpop,
        )
        val loop = DriverLoop(
          arrivals = ArrivalProcess.startingAt(anchor, random.split()),
          rate = schedule.envelope(shardRate),
          pool = PlanUserPool.of(engine.pool, engine.shard.index, engine.plan),
          busy = engine.busy,
          runner = runner,
          recorder = engine.recorder,
          lag = engine.lag,
          random = random.split(),
          queueCapacity = queueCapacity,
          tally = engine.tally,
        )
        for
          _ <- ZIO.logInfo(
            f"Scheduling ${shardRate}%.3f session arrivals/s on shard ${engine.shard.index} " +
              s"of ${generation.shardCount}, anchored at $anchor (epoch ${generation.epoch})",
          )
          // Its own scope, so interrupting the fiber on a restart also interrupts every session
          // the loop forked into it.
          fiber <- ZIO
            .scoped(loop.run)
            .catchAllCause: cause =>
              // An interrupt is this driver's own restart or shutdown, not a fault.
              ZIO.unless(cause.isInterrupted)(failed.failCause(cause)).unit
            .forkScoped
          _ <- running.set(Some(Running(generation, fiber)))
        yield ()
    yield ()

  /** The fleet's session arrival rate, before this driver's share is taken: the two platform
    * streams added, because one [[DriverLoop]] schedules both and the platform of each arrival is
    * decided by the user the pool hands out, not by the stream it came from.
    */
  def sessionRateOf(plan: LoadPlan): Double =
    plan.scenarios
      .filter(rate => rate.scenario == PlanScenario.MobileSession || rate.scenario == PlanScenario.WebSession)
      .map(_.basePerSecond)
      .sum

  private def warnAboutRegistration(plan: LoadPlan): UIO[Unit] =
    val registration = plan.scenarios.find(_.scenario == PlanScenario.Registration).map(_.basePerSecond).getOrElse(0.0)
    ZIO
      .logWarning(
        f"The plan publishes $registration%.3f registrations/s, which no driver schedules; " +
          "the ramp is seeded, not driven",
      )
      .when(registration > 0.0)
      .unit

  /** Polls until the coordinator answers once.
    *
    * A driver cannot start on a plan it has never seen -- it would have no campaign anchor and no
    * shard map -- so this is the one place the coordinator is a hard dependency. It retries rather
    * than failing because a driver and its coordinator are rolled out together, and a pod that
    * crash-looped through the seconds before the coordinator was ready would be restarting for
    * reasons unrelated to the campaign.
    */
  private def firstPlan(client: PlanClient, pollInterval: Duration): ZIO[Any, Nothing, LoadPlan] =
    client.fetch
      .tapErrorCause(ZIO.logWarningCause("Waiting for the coordinator's first plan", _))
      .retry(Schedule.spaced(pollInterval))
      .orDie

  private def healthReporter(lag: ScheduleLag, busy: BusyUsers, buffer: WriteBehindBuffer): UIO[DriverHealthReporter] =
    DriverHealthSource
      .from(
        scheduleLagByScenario = lag.current.map(current => Map(DriverLoop.scenarioLabel -> current)),
        busyUsers = busy.size,
        inflightRequests = InflightRequests.current,
        storeFlushDroppedTotal = buffer.droppedTotal,
      )
      .flatMap(DriverHealthReporter.make)

  private def storeTransactor: ZIO[Scope & ConfigProvider, Throwable, TransactorZIO] =
    PostgresHikariDataSource
      .transactor(
        serviceName = Some("loadgen-driver"),
        // Eight drivers racing Flyway on startup is a lock convoy at best, and the coordinator has
        // already applied the schema by the time any of them schedules anything. `false` still
        // validates this build's migrations against the live database.
        migrate = false,
        validateOnMigrate = true,
        migrationLocations = Some(LoadgenMigrations.locations),
        configPath = LoadgenStore.configPath,
      )
      .build
      .map(_.get[TransactorZIO])

  /** The driver's id, in `vu_metric_snapshots.driver_id` and on every report.
    *
    * The shard index, not the pod name: a driver is identified by the slice it owns, so a replaced
    * pod reports under the same id and the coordinator carries its predecessor's tallies forward
    * (`DriverRegistry.restarted`) instead of counting a new fleet member whose counters began
    * at zero.
    */
  def identityOf(shard: ShardConfig): String = s"driver-${shard.index}"

  /** The campaign's draws, pinned. Seeded from the campaign name and the shard index so that two
    * runs of one campaign draw the same think times and action counts on the same shard, and two
    * shards of one campaign do not draw the same ones.
    */
  def seedOf(campaign: String, shard: ShardConfig): Long =
    campaign.hashCode.toLong * 31L + shard.index.toLong

  private def scenarioClientsOf(clients: ClientsConfig): ScenarioClients =
    ScenarioClients(
      mobileOtp = clients.mobileOtp,
      mobileOtpPassword = clients.mobileOtpPassword,
      mobilePasskey = clients.mobilePasskey,
      webPreset = PresetId(clients.webPreset),
      scope = clients.scope,
    )

  private def protocolFlows(
      config: LoadgenConfig,
      clients: ClientsConfig,
      recorder: ScenarioRecorder,
      client: Client,
  ): ZIO[Any, Throwable, ProtocolFlows] =
    val registry = registryOf(clients)
    val otpCode = Otp.nonProd(clients.otpLength)
    for
      auth <- HttpAuthClient
        .make(client, config.targets, registry, LoadgenHttpClient.requestTimeout)
        .mapError(error => InvalidDriverConfig(error.toString))
      actionClient <- EdgeActionClient
        .make(client, config.targets, LoadgenHttpClient.requestTimeout)
        .mapError(error => InvalidDriverConfig(error.toString))
      edge <- HttpEdgeClient
        .make(client, config.targets, actionClient, LoadgenHttpClient.requestTimeout)
        .mapError(error => InvalidDriverConfig(error.toString))
    yield ProtocolFlows(
      mobile = MobileFlows(auth, actionClient, registry, recorder, otpCode, config.targets.origin),
      web = WebFlows(edge, auth, recorder, otpCode, config.targets.origin),
    )

  /** The three mobile clients, public and authenticating with PKCE alone (design doc §2.2).
    *
    * The web client is absent on purpose: a web session logs in *through edge*, which holds that
    * client's secret and runs the code exchange itself (§8.4), so a driver carrying those
    * credentials could take a path no browser can.
    */
  private def registryOf(clients: ClientsConfig): ClientRegistry =
    def registration(clientId: String): ClientRegistration =
      ClientRegistration(ClientCreds(clientId, None), clients.mobileRedirectUri)
    ClientRegistry(
      byId = Map(
        clients.mobileOtp -> registration(clients.mobileOtp),
        clients.mobileOtpPassword -> registration(clients.mobileOtpPassword),
        clients.mobilePasskey -> registration(clients.mobilePasskey),
      ),
      defaultClientId = clients.mobileOtp,
    )

case class MissingDriverConfig(block: String)
  extends RuntimeException(s"role = driver requires a '$block' configuration block")

case class InvalidDriverConfig(reason: String) extends RuntimeException(reason)
