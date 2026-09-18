package versola.loadgen.coordinator

import versola.loadgen.config.{CampaignConfig, LoadgenConfig, MeasurementRefConfig, PlanConfig}
import versola.loadgen.metrics.{
  AcceptanceThresholds,
  CampaignReport,
  CampaignRun,
  LatencyThreshold,
  LoadgenMetrics,
  MeasurementId,
  ObservedAccessTokenTtl,
  PopulationState,
  RunPhase,
  TokenMode,
}
import versola.loadgen.model.VirtualUserState
import versola.loadgen.scheduler.{CampaignSchedule, DiurnalEnvelope}
import versola.loadgen.store.{MetricSnapshotRepository, SutStatPhase, VirtualUserRepository}
import versola.loadgen.sut.{PoolerQueuePeak, PoolerQueueRecorder, PoolerStatsCapture, PoolerStatsDelta, SutStatsCapture, SutStatsDelta}
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
    sutStats: Option[SutStatsCapture],
    poolerStats: Option[PoolerStatsCapture],
    transitionLock: Semaphore,
):

  def plan: Task[LoadPlan] =
    for
      now <- Clock.instant
      state <- control.get
      factor <- registrationFactor.get
      registered <- registeredCount
      plan <- ZIO.fromEither(planAt(now, state, factor, registered)).mapError(IllegalStateException(_))
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
      fleet <- drivers.view(now, current.shards.epoch)
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
      staleEpochDrivers = fleet.staleEpochDrivers,
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
        state <- control.get
        fleet <- drivers.view(now, state.shards.epoch)
        counts <- population.get
        databases <- sutDeltas(name)
        poolers <- poolerDeltas(name)
        queue <- poolerPeaks(name)
        report <- ZIO
          .fromEither(
            CampaignReport.assemble(
              name,
              reports,
              fleet.taxonomy,
              fleet.health,
              runOf(state, counts, fleet),
              thresholds,
              databases,
              poolers,
              queue,
            ),
          )
          .mapError(IllegalStateException(_))
      yield report

  /** The run's header: the plan as published, the population as counted, and what the SUT said
    * about the tokens it issued.
    *
    * The shard map is the one in force rather than `plan.shard-count` from config, for the same
    * reason the population is a count rather than `population.target`: a campaign that was
    * rebalanced, or seeded short, would otherwise report the run somebody intended instead of the
    * one that happened.
    */
  private def runOf(state: CampaignControl, counts: Map[VirtualUserState, Long], fleet: FleetView): CampaignRun =
    CampaignRun(
      phases = campaign.phases.map: phase =>
        RunPhase(
          name = phase.name,
          durationMillis = phase.duration.toMillis,
          scale = phase.scale,
          scaleFrom = phase.scaleFrom,
          scaleTo = phase.scaleTo,
        ),
      population = counts.map((state, count) => state.toString.toLowerCase -> count),
      shardCount = state.shards.shardCount,
      shardEpoch = state.shards.epoch,
      tokenMode = TokenMode.Bearer,
      observedTokenTypes = fleet.observed.tokenTypes.toList.sorted,
      accessTokenTtls = fleet.observed.accessTokenTtlsByClient.toList.sorted.map: (clientId, ttls) =>
        ObservedAccessTokenTtl(clientId, ttls.toList.sorted),
    )

  /** §3 of the report, or `None` when there is nothing to show: no SUT credentials, or a campaign
    * that has not been stopped and so has only the snapshot it opened with.
    *
    * An empty list is collapsed to `None` rather than serialised as `[]`, so a report carries the
    * section only when the section says something.
    */
  private def sutDeltas(name: String): Task[Option[List[SutStatsDelta]]] =
    ZIO.foreach(sutStats)(_.deltas(name)).map(_.filter(_.nonEmpty))

  /** §4's pooler half, on [[sutDeltas]]'s conditions and independently of them: a stack with a
    * PgBouncer and no SUT credentials reports this section and not §3.
    */
  private def poolerDeltas(name: String): Task[Option[List[PoolerStatsDelta]]] =
    ZIO.foreach(poolerStats)(_.deltas(name)).map(_.filter(_.nonEmpty))

  /** §4's sampled half, which unlike [[poolerDeltas]] is already worth reporting mid-run: the
    * peaks accumulate from the opening boundary and every reading taken so far is one the report
    * can state. Empty until a campaign has been started, which is when the accumulation is armed.
    */
  private def poolerPeaks(name: String): UIO[Option[List[PoolerQueuePeak]]] =
    ZIO.foreach(poolerStats)(_.peaks(name)).map(_.filter(_.nonEmpty))

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

  /** The coordinator's timers. Forked into the caller's scope, so they are interrupted by the
    * same shutdown that takes the server down.
    *
    * The pooler sampler is forked unconditionally rather than only for a configured pooler: it
    * reads `poolerStats` through [[sampleQueues]], which is `None` for a coordinator without a
    * `pooler-stats` block, and a timer whose tick is a no-op costs less than a second wiring
    * path that has to be kept in step with the first one.
    */
  def run: ZIO[Scope, Nothing, Unit] =
    for
      _ <- settle.repeat(Schedule.spaced(CoordinatorService.settleInterval)).forkScoped
      _ <- controllerTick.repeat(Schedule.spaced(RegistrationController.interval)).forkScoped
      _ <- sampleQueues.repeat(Schedule.spaced(PoolerQueueRecorder.sampleInterval)).forkScoped
    yield ()

  /** One tick of §4's queue sampler, and the decision that only a running campaign is sampled.
    *
    * Matched on the state rather than left to the accumulation's armed flag, which would let a
    * paused campaign go on sampling an idle pooler. A pause is time the operator took out of the
    * run, and folding its empty readings into the distribution would pull every quantile towards
    * zero by an amount that says nothing about the SUT -- the same argument
    * [[versola.loadgen.sut.PoolerQueueRecorder]] makes for dropping readings taken before the
    * campaign opened. A stopped campaign is excluded by the same match, so the peaks a report
    * shows after a stop are the run's and stay put however long the coordinator lives on.
    */
  private def sampleQueues: UIO[Unit] =
    control.get.flatMap: state =>
      ZIO.foreachDiscard(poolerStats)(_.sample).when(state.state == CampaignState.Running).unit

  /** Serialised by [[transitionLock]] end to end, capture included, and not just across the read
    * of `control` and its write: `GET /plan` reads `control` with no lock of its own (§12's
    * drivers cannot be made to take one), so a driver polling between an unlocked write and the
    * capture that is supposed to precede it would receive a running plan and start traffic while
    * the "before" snapshot was still being read, pulling that early traffic into the reading the
    * report presents as the campaign's opening state. Holding `control`'s new value back until
    * the capture completes is what a driver's poll is racing against; a plain `Ref` gives that no
    * help; a lock a driver never has to touch does.
    */
  private def transition(
      move: (CampaignControl, Instant) => Either[String, CampaignControl],
  ): IO[CoordinatorRefusal | Throwable, LoadPlan] =
    transitionLock.withPermit:
      for
        now <- Clock.instant
        current <- control.get
        next <- ZIO.fromEither(move(current, now)).mapError(CoordinatorRefusal.Conflict(_))
        _ <- captureSutStats(current.state, next.state)
        _ <- control.set(next)
        factor <- registrationFactor.get
        registered <- registeredCount
        plan <- ZIO.fromEither(planAt(now, next, factor, registered)).mapError(IllegalStateException(_))
        _ <- ZIO.logInfo(s"Campaign '${campaign.name}' is ${plan.state.label} (epoch ${plan.shards.epoch})")
      yield plan

  /** The campaign's two boundaries, and only those two: 07-wal-tuning.md measures "до и после при
    * фиксированном числе транзакций", and a run is bracketed by the operator's start and stop.
    *
    * A resume is not a start. `CampaignControl.start` serves both -- it is how a pause ends -- and
    * re-capturing there would move the opening reading forward into the middle of the run, so the
    * transition is matched on rather than the command. Stopping an idle campaign still captures:
    * it costs one query and the pairing in [[SutStatsDelta.from]] discards the unmatched row.
    *
    * Awaited rather than forked, so that a `GET /report` issued straight after the stop it was
    * waiting for sees the closing snapshot. That is a few queries against the SUT on a boundary
    * an operator is already waiting on, against a race that would silently produce a report with
    * no §3 in it.
    *
    * Called by [[transition]] before it commits `control`'s new value, not after: see that
    * method's own doc for why the "before" capture in particular has to precede the write a
    * driver's `GET /plan` can observe.
    */
  private def captureSutStats(previous: CampaignState, current: CampaignState): UIO[Unit] =
    ZIO.foreachDiscard(boundaryOf(previous, current)): phase =>
      // The pooler is read after the databases rather than in parallel with them. Both readings
      // are of the same instant only approximately, and where they disagree the database's is
      // the one the report leans on -- so the pooler's boundary is the one that should absorb
      // the other's latency, not the one that adds to it.
      ZIO.foreachDiscard(sutStats)(_.capture(campaign.name, phase)) *>
        ZIO.foreachDiscard(poolerStats)(_.capture(campaign.name, phase))

  /** Which boundary, if either, a transition is. Shared by both captures so that §3 and §4 can
    * never end up bracketing different things.
    */
  private def boundaryOf(previous: CampaignState, current: CampaignState): Option[SutStatPhase] =
    (previous, current) match
      case (CampaignState.Idle, CampaignState.Running) => Some(SutStatPhase.Before)
      case (before, CampaignState.Stopped) if before != CampaignState.Stopped => Some(SutStatPhase.After)
      case _ => None

  /** The ramp only runs while the campaign does: a paused campaign's registered count stops
    * moving, and a controller still comparing it against an advancing curve would wind the factor
    * up to its ceiling and resume at double rate for no reason.
    */
  private def registrationAnchor(state: CampaignControl): Option[Instant] =
    if !campaign.registration.enabled || state.state != CampaignState.Running then None else state.startedAt

  private def registeredCount: UIO[Long] =
    population.get.map(_.getOrElse(VirtualUserState.Registered, 0L))

  /** The ramp is over once it has run for its configured duration or has produced its target,
    * and a ramp that is over publishes no registration rate at all.
    *
    * The controller cannot express this on its own: `RegistrationCurve` clamps its planned count
    * at the target, so a minute after the ramp lands the error term is zero, the factor is 1.0,
    * and §12's λ_nominal would go on registering users through every later phase of the
    * campaign -- past the population the whole capacity model is stated against. "Lands the ramp
    * on exactly 1M" (§12) is a statement about where it stops, not only about how it gets there.
    *
    * Read off the clock and the population count rather than a flag, so a coordinator that took
    * over mid-campaign reaches the same answer as the one that started it.
    */
  private def rampComplete(anchor: Instant, now: Instant, registered: Long): Boolean =
    Duration.fromInterval(anchor, now).toMillis >= campaign.registration.duration.toMillis ||
      registered >= campaign.registration.target

  /** @return `Left` only for a campaign config the schedule cannot represent, which
    *         [[CoordinatorService.make]] has already rejected -- the phases and the diurnal
    *         envelope are the whole of what `CampaignSchedule.from` validates, and neither
    *         depends on the anchor this passes it.
    */
  private def planAt(
      now: Instant,
      state: CampaignControl,
      factor: Double,
      registered: Long,
  ): Either[String, LoadPlan] =
    val anchor = state.startedAt.getOrElse(now)
    CampaignSchedule.from(campaign, anchor).map: schedule =>
      val running = state.state == CampaignState.Running
      val ramping = !rampComplete(anchor, now, registered)
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
            val base =
              if scenario != PlanScenario.Registration then nominal
              else if ramping then nominal * factor
              else 0.0
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
      sutStats: Option[SutStatsCapture],
      poolerStats: Option[PoolerStatsCapture],
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
      transitionLock <- Semaphore.make(1L)
    yield CoordinatorService(
      campaign = config.campaign,
      pollInterval = config.coordinator.pollInterval,
      rates = rates,
      diurnal = diurnal,
      thresholds = AcceptanceThresholds.designDefaults(
        latency = plan.acceptance.latency.map: threshold =>
          LatencyThreshold(measurementOf(MeasurementRefConfig(threshold.scenario, threshold.name)), threshold.ceiling),
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
      sutStats = sutStats,
      poolerStats = poolerStats,
      transitionLock = transitionLock,
    )

  /** §11's two granularities, as configuration names them: a step belongs to a scenario, a flow
    * is named on its own.
    */
  private def measurementOf(ref: MeasurementRefConfig): MeasurementId =
    ref.scenario match
      case Some(scenario) => MeasurementId.Step(scenario, ref.name)
      case None => MeasurementId.Flow(ref.name)
