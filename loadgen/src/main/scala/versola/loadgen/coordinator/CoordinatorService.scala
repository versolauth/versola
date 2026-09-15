package versola.loadgen.coordinator

import versola.loadgen.config.{CampaignConfig, LoadgenConfig, MeasurementRefConfig, PlanConfig}
import versola.loadgen.metrics.{
  AcceptanceThresholds,
  CampaignReport,
  LoadgenMetrics,
  MeasurementId,
  PopulationState,
}
import versola.loadgen.model.VirtualUserState
import versola.loadgen.scheduler.{CampaignSchedule, DiurnalEnvelope}
import versola.loadgen.store.{MetricSnapshotRepository, VirtualUserRepository}
import zio.*

import java.time.Instant

/** A refusal an operator caused and can fix: rebalancing a stopped campaign, pausing one that
  * never started, asking for a report on a campaign this coordinator is not running. Distinct
  * from a `Throwable` so the routes can answer for it without a 500 and without matching on
  * messages.
  *
  * The two cases say *why* rather than carrying a status code: the service has no business
  * knowing it is behind HTTP, and the distinction it does own -- "the thing you named is not
  * here" against "it is here and cannot do that now" -- is exactly the one a caller needs.
  */
enum CoordinatorRefusal(val reason: String):
  case Conflict(override val reason: String) extends CoordinatorRefusal(reason)
  case NotFound(override val reason: String) extends CoordinatorRefusal(reason)

/** The coordinator of dev spec §12: the campaign plan, the shard map and its drain protocol, the
  * registration controller, and the merge behind `GET /status` and `GET /report/{campaign}`.
  *
  * What it does *not* hold is the point of the design. There is no per-user state here, no shard
  * lease, no session, no token -- a driver owns its slice by arithmetic on the id (§7.1), so the
  * coordinator dying costs the fleet nothing it needs to keep generating load: drivers carry on
  * against the last plan they fetched and a standby answers the next poll (§12). What it does
  * lose is the campaign's start instant, a drain in flight, and the live fleet view -- which is
  * why the campaign's *measurements* never live here: the histograms are in
  * `vu_metric_snapshots`, the only `LOGGED` table in the schema, and are re-read on every report.
  *
  * @param pollInterval
  *   published to the drivers rather than assumed by them, so the one number that decides how
  *   long a stale plan can be in force is stated by the process that owns the plan.
  */
final class CoordinatorService private (
    campaign: CampaignConfig,
    pollInterval: Duration,
    rates: ScenarioRates,
    diurnal: DiurnalEnvelope,
    thresholds: AcceptanceThresholds,
    control: Ref[CampaignControl],
    registrationFactor: Ref[Double],
    population: Ref[Map[VirtualUserState, Long]],
    drivers: DriverRegistry,
    users: VirtualUserRepository,
    snapshots: MetricSnapshotRepository,
    rebalancer: ShardRebalancer,
):

  def plan: Task[LoadPlan] =
    for
      now <- Clock.instant
      state <- control.get
      factor <- registrationFactor.get
      plan <- ZIO.fromEither(planAt(now, state, factor)).mapError(IllegalStateException(_))
    yield plan

  def start: IO[CoordinatorRefusal | Throwable, LoadPlan] = transition(_.start(_))

  def pause: IO[CoordinatorRefusal | Throwable, LoadPlan] = transition(_.pause(_))

  def stop: IO[CoordinatorRefusal | Throwable, LoadPlan] = transition((state, _) => state.stop)

  /** Phase one of the rebalance. The drain deadline is computed here, from the request's window
    * and this process's clock, so every driver reads one absolute instant instead of each
    * offsetting a duration against its own clock at the moment its poll happens to land.
    */
  def rebalance(request: RebalanceRequest): IO[CoordinatorRefusal | Throwable, LoadPlan] =
    for
      now <- Clock.instant
      drainUntil = now.plusMillis(math.max(request.drainMillis, 0L))
      plan <- transition((state, at) => state.publishRebalance(request.shardCount, drainUntil, at))
      _ <- ZIO.logInfo(
        s"Published shard map epoch ${plan.pendingShards.map(_.epoch).getOrElse(plan.shards.epoch)} " +
          s"at ${request.shardCount} shards, draining until $drainUntil",
      )
    yield plan

  def acceptDriverReport(report: DriverReport): IO[CoordinatorRefusal, Unit] =
    drivers.accept(report).mapError(CoordinatorRefusal.Conflict(_))

  def status: Task[CoordinatorStatus] =
    for
      now <- Clock.instant
      current <- plan
      fleet <- drivers.view(now)
      rows <- snapshots.loadCampaign(campaign.name, now.minusMillis(CoordinatorService.statusWindow.toMillis))
      latency <- ZIO.fromEither(SnapshotMerge.summaries(rows)).mapError(IllegalStateException(_))
      counts <- population.get
    yield CoordinatorStatus(
      campaign = campaign.name,
      state = current.state,
      phase = current.phase,
      shards = current.shards,
      pendingShards = current.pendingShards,
      drivers = fleet.drivers,
      staleDrivers = fleet.staleDrivers,
      scenarios = current.scenarios.map: scenario =>
        ScenarioProgress(
          scenario = scenario.scenario,
          plannedPerSecond = scenario.ratePerSecond,
          achievedPerSecond = fleet.achievedPerSecond.get(scenario.scenario),
        ),
      latency = latency,
      taxonomy = fleet.taxonomy,
      health = fleet.health,
      population = counts.map((state, count) => state.toString.toLowerCase -> count),
    )

  /** `GET /report/{campaign}` (§12): the whole campaign's snapshots, merged, judged.
    *
    * Only this coordinator's own campaign. The latency is in the store and any process could
    * merge it, but the verdict also rests on the error taxonomy and the driver-health readings,
    * and those reach a coordinator only through live driver reports -- so a verdict for some
    * other campaign would be a report with the two criteria that do not describe the SUT's
    * latency silently missing. Refusing says so instead.
    */
  def report(name: String): IO[CoordinatorRefusal | Throwable, CampaignReport] =
    if name != campaign.name then
      ZIO.fail(
        CoordinatorRefusal.NotFound(
          s"this coordinator is running campaign '${campaign.name}'; " +
            s"a verdict for '$name' would carry no error taxonomy and no driver health",
        ),
      )
    else
      for
        now <- Clock.instant
        // Instant.EPOCH, not the campaign's start: the start is in memory and a restarted
        // coordinator does not have it, while the rows carry their own campaign name. Reading
        // from the epoch is what makes the report independent of this process's uptime.
        rows <- snapshots.loadCampaign(name, Instant.EPOCH)
        _ <- ZIO
          .fail(CoordinatorRefusal.NotFound(s"no latency snapshots have been recorded for campaign '$name'"))
          .when(rows.isEmpty)
        reports <- ZIO.fromEither(SnapshotMerge.toReports(rows)).mapError(IllegalStateException(_))
        fleet <- drivers.view(now)
        report <- ZIO
          .fromEither(CampaignReport.assemble(name, reports, fleet.taxonomy, fleet.health, thresholds))
          .mapError(IllegalStateException(_))
      yield report

  /** Phase two of the rebalance, once the drain window has elapsed: rewrite `vu_users.shard`,
    * then promote the published map.
    *
    * A failed `UPDATE` leaves the pending map in place and is retried on the next tick rather
    * than abandoned. Abandoning it would leave the drivers draining users nobody is ever going to
    * take over -- a fleet that quietly stops generating load for part of its population, which is
    * the failure this whole protocol is written to avoid.
    */
  def settle: UIO[Unit] =
    Clock.instant.flatMap: now =>
      control.get.map(_.dueRebalance(now)).flatMap:
        case None => ZIO.unit
        case Some(change) =>
          rebalancer
            .reassign(change.shardCount)
            .flatMap: rewritten =>
              control.update(_.commitRebalance(change)) *>
                ZIO.logInfo(
                  s"Shard map epoch ${change.epoch} in force at ${change.shardCount} shards ($rewritten users moved)",
                )
            .catchAllCause: cause =>
              ZIO.logErrorCause(
                s"Re-sharding onto ${change.shardCount} shards failed; the drain stays in force and will be retried",
                cause,
              )

  /** One tick of the registration controller (§12) and of the population counts `GET /status`
    * publishes -- one `GROUP BY state` serves both, because they are the same reading.
    *
    * The count comes from the emulator's own store, never the SUT's (design doc §2.4). Counting
    * users in auth would measure the outbox's lag as a registration shortfall and make the
    * controller chase it.
    */
  def controllerTick: UIO[Unit] =
    (for
      now <- Clock.instant
      counts <- users.countByState
      _ <- population.set(counts)
      _ <- ZIO.foreachDiscard(CoordinatorService.populationStates): (state, published) =>
        LoadgenMetrics.population(published, counts.getOrElse(state, 0L))
      state <- control.get
      _ <- ZIO.foreachDiscard(registrationAnchor(state)): startedAt =>
        val planned = RegistrationCurve
          .from(diurnal, startedAt, campaign.registration)
          .plannedAt(Duration.fromInterval(startedAt, now))
        val actual = counts.getOrElse(VirtualUserState.Registered, 0L)
        val factor = RegistrationController.factor(planned, actual)
        registrationFactor.set(factor) *>
          ZIO.logInfo(
            f"Registration controller: planned $planned%.0f, registered $actual, " +
              f"lambda factor $factor%.3f of ${ScenarioRates.registrationPerSecond(campaign)}%.3f/s nominal",
          )
    yield ()).catchAllCause: cause =>
      // A failed tick must not take the loop down with it: the store being briefly unreachable is
      // a reason to keep the last factor, not to stop controlling the ramp for the rest of the
      // campaign.
      ZIO.logErrorCause("Registration controller tick failed; keeping the last published factor", cause)

  /** The coordinator's two timers. Forked into the caller's scope, so they are interrupted by the
    * same shutdown that takes the server down.
    */
  def run: ZIO[Scope, Nothing, Unit] =
    for
      _ <- settle.repeat(Schedule.spaced(CoordinatorService.settleInterval)).forkScoped
      _ <- controllerTick.repeat(Schedule.spaced(RegistrationController.interval)).forkScoped
    yield ()

  private def transition(
      move: (CampaignControl, Instant) => Either[String, CampaignControl],
  ): IO[CoordinatorRefusal | Throwable, LoadPlan] =
    for
      now <- Clock.instant
      moved <- control.modify: state =>
        move(state, now) match
          case Left(reason) => (Left(CoordinatorRefusal.Conflict(reason)), state)
          case Right(next) => (Right(next), next)
      state <- ZIO.fromEither(moved)
      factor <- registrationFactor.get
      plan <- ZIO.fromEither(planAt(now, state, factor)).mapError(IllegalStateException(_))
      _ <- ZIO.logInfo(s"Campaign '${campaign.name}' is ${plan.state.label} (epoch ${plan.shards.epoch})")
    yield plan

  /** The ramp only runs while the campaign does: a paused campaign's registered count stops
    * moving, and a controller still comparing it against an advancing curve would wind the factor
    * up to its ceiling and resume at double rate for no reason.
    */
  private def registrationAnchor(state: CampaignControl): Option[Instant] =
    if !campaign.registration.enabled || state.state != CampaignState.Running then None else state.startedAt

  /** @return `Left` only for a campaign config the schedule cannot represent, which
    *         [[CoordinatorService.make]] has already rejected -- the phases and the diurnal
    *         envelope are the whole of what `CampaignSchedule.from` validates, and neither
    *         depends on the anchor this passes it.
    */
  private def planAt(now: Instant, state: CampaignControl, factor: Double): Either[String, LoadPlan] =
    CampaignSchedule.from(campaign, state.startedAt.getOrElse(now)).map: schedule =>
      val running = state.state == CampaignState.Running
      LoadPlan(
        campaign = campaign.name,
        state = state.state,
        phase = if running then schedule.phaseAt(now).map(_.name) else None,
        startedAtEpochMillis = state.startedAt.map(_.toEpochMilli),
        publishedAtEpochMillis = now.toEpochMilli,
        pollIntervalMillis = pollInterval.toMillis,
        scenarios = rates.basePerSecond.toList
          .sortBy((scenario, _) => scenario.label)
          .map: (scenario, nominal) =>
            // Only the registration stream carries the controller's factor. Applying it to the
            // session streams would let an error-attrition correction on the ramp move the load
            // the campaign is actually measuring.
            val base = if scenario == PlanScenario.Registration then nominal * factor else nominal
            // A campaign that is not running publishes zero, not its base rate: an idle, paused or
            // stopped campaign must generate nothing, and a driver that read a non-zero base here
            // would schedule against it the moment its own clock passed the campaign's start.
            if running then ScenarioRate(scenario, base, schedule.rateAt(base, now))
            else ScenarioRate(scenario, 0.0, 0.0)
        ,
        shards = state.shards,
        pendingShards = state.pendingShards,
      )

object CoordinatorService:

  /** How often the drain deadline is checked. A second, because the deadline is an instant an
    * operator chose and the epoch boundary should not drift visibly past it; the check is one
    * `Ref` read on the ordinary tick.
    */
  val settleInterval: Duration = 1.second

  /** How much of the campaign `GET /status`' quantiles cover. The live view answers "what is the
    * SUT doing now", which a merge over ten hours cannot: a regression in the last ten minutes is
    * invisible in a campaign-wide p99. `GET /report/{campaign}` is the one that merges everything.
    */
  val statusWindow: Duration = 5.minutes

  /** A driver whose last report is older than this is listed as stale rather than counted in the
    * achieved rate. Three poll intervals: one missed poll is a scrape landing badly, three is a
    * driver that has stopped talking.
    */
  def staleAfter(pollInterval: Duration): Duration = pollInterval * 3L

  private val populationStates: List[(VirtualUserState, PopulationState)] = List(
    VirtualUserState.Planned -> PopulationState.Planned,
    VirtualUserState.Registered -> PopulationState.Registered,
    VirtualUserState.Broken -> PopulationState.Broken,
  )

  /** Boot-time validation is deliberately everything the plan computation could otherwise fail
    * on: the population mix, the registration ramp, the phases and the diurnal envelope. A
    * coordinator that boots is one whose every subsequent `/plan` is a total function of the
    * clock and the operator's commands -- the alternative is a fleet of drivers polling a process
    * that answers 500 for the whole campaign.
    */
  def make(
      config: LoadgenConfig,
      users: VirtualUserRepository,
      snapshots: MetricSnapshotRepository,
      rebalancer: ShardRebalancer,
  ): IO[String, CoordinatorService] =
    for
      plan <- ZIO.fromOption(config.plan).orElseFail("role = coordinator requires a 'plan' configuration block")
      _ <- ZIO.fromEither(PlanConfig.validate(plan))
      rates <- ZIO.fromEither(ScenarioRates.from(config.population, config.campaign, plan.shardCount))
      diurnal <- ZIO.fromEither(DiurnalEnvelope.from(config.campaign.diurnal))
      _ <- ZIO.fromEither(CampaignSchedule.from(config.campaign, Instant.EPOCH))
      control <- Ref.make(CampaignControl.initial(plan.shardCount))
      factor <- Ref.make(1.0)
      population <- Ref.make(Map.empty[VirtualUserState, Long])
      registry <- DriverRegistry.make(config.campaign.name, staleAfter(config.coordinator.pollInterval))
    yield CoordinatorService(
      campaign = config.campaign,
      pollInterval = config.coordinator.pollInterval,
      rates = rates,
      diurnal = diurnal,
      thresholds = AcceptanceThresholds.designDefaults(
        tokenRefresh = measurementOf(plan.acceptance.tokenRefresh),
        edgeProxy = measurementOf(plan.acceptance.edgeProxy),
        mockBackend = measurementOf(plan.acceptance.mockBackend),
      ),
      control = control,
      registrationFactor = factor,
      population = population,
      drivers = registry,
      users = users,
      snapshots = snapshots,
      rebalancer = rebalancer,
    )

  /** §11's two granularities, as configuration names them: a step belongs to a scenario, a flow
    * is named on its own.
    */
  private def measurementOf(ref: MeasurementRefConfig): MeasurementId =
    ref.scenario match
      case Some(scenario) => MeasurementId.Step(scenario, ref.name)
      case None => MeasurementId.Flow(ref.name)
