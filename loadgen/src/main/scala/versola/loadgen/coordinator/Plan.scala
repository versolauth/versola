package versola.loadgen.coordinator

import versola.loadgen.metrics.{CampaignHealth, ErrorTaxonomy, LatencySummary}
import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

/** Where the campaign is in its lifecycle, as `POST /campaign/start|pause|stop` moves it
  * (versola-loadgen-dev-spec.md §12).
  *
  * `Stopped` is terminal. A stopped campaign cannot be restarted through this API because the
  * plan's anchor and its shard-map epoch would carry over into what is, for the report, a
  * different campaign -- one whose `vu_metric_snapshots` rows are already interleaved with the
  * first run's under the same `campaign` name.
  */
enum CampaignState(val label: String):
  case Idle extends CampaignState("idle")
  case Running extends CampaignState("running")
  case Paused extends CampaignState("paused")
  case Stopped extends CampaignState("stopped")

object CampaignState:
  private val byLabel: Map[String, CampaignState] = values.map(state => state.label -> state).toMap

  // Same idiom as `PlannedOutcome`'s: the label is the wire form, so a dashboard, a driver and a
  // stored plan all name the state the same way.
  given JsonCodec[CampaignState] =
    JsonCodec.string.transformOrFail(
      label => byLabel.get(label).toRight(s"unknown campaign state: $label"),
      _.label,
    )

/** The arrival streams the coordinator publishes a rate for (§7.2: "for each scenario the
  * coordinator publishes a rate λ(t); the driver's share is λ(t) / shardCount").
  *
  * These are *arrival* streams, not the scenario engine's measurement labels. One session
  * arrival runs a login or a refresh, some actions, possibly a step-up and a logout, and each of
  * those is separately measured -- but none of them is separately scheduled, because the open
  * model schedules sessions and lets the state machine decide what a session does (design doc
  * §2.3). Publishing a rate per measured scenario would be publishing a rate for consequences
  * the driver does not control.
  *
  * The split is by platform because that is the one branch the coordinator can price: mobile and
  * web sessions differ in login probability, action count and logout probability (§5's
  * `session` block), so a single blended rate could not be re-derived per platform by a driver
  * without re-deriving the population mix too.
  */
enum PlanScenario(val label: String):
  case MobileSession extends PlanScenario("mobile-session")
  case WebSession extends PlanScenario("web-session")
  case Registration extends PlanScenario("registration")

object PlanScenario:
  private val byLabel: Map[String, PlanScenario] = values.map(scenario => scenario.label -> scenario).toMap

  given JsonCodec[PlanScenario] =
    JsonCodec.string.transformOrFail(
      label => byLabel.get(label).toRight(s"unknown plan scenario: $label"),
      _.label,
    )

  // A driver reports its arrivals as a map keyed by scenario, so the label has to serve as a
  // JSON field name as well -- same pair of derivations, and the same reason, as
  // `PlannedOutcome`'s.
  given JsonFieldEncoder[PlanScenario] = JsonFieldEncoder.string.contramap(_.label)

  given JsonFieldDecoder[PlanScenario] =
    JsonFieldDecoder.string.mapOrFail(label => byLabel.get(label).toRight(s"unknown plan scenario: $label"))

/** One scenario's published rate, in both the forms a driver needs.
  *
  * @param basePerSecond
  *   λ before the phase scale and the diurnal envelope, for the whole fleet. This is the input to
  *   `CampaignSchedule.envelope`, which is what the driver's thinning loop needs: λ(t) has to be
  *   evaluable *between* polls, and a rate sampled at publish time and held for ten seconds is
  *   exactly the coordinated-omission-flavoured error §7.2's thinning exists to avoid. The
  *   coordinator's contribution to this number is the registration controller's factor, which a
  *   driver cannot compute because it cannot see the whole population's registered count.
  * @param ratePerSecond
  *   λ at [[LoadPlan.publishedAtEpochMillis]], with the phase scale and the diurnal envelope
  *   applied. Not what the driver schedules against -- it is the planned rate `GET /status`
  *   compares the fleet's achieved rate to, and it is how an operator reads the plan without
  *   re-running the envelope by hand.
  */
case class ScenarioRate(scenario: PlanScenario, basePerSecond: Double, ratePerSecond: Double) derives JsonCodec

/** Who owns which virtual users: `shard = id % shardCount` over the dense `vu_users.id` (§7.1).
  *
  * `epoch` is the map's generation, bumped only when `shardCount` changes and never reused. It is
  * what lets a driver notice that its slice is no longer the slice it loaded: the map is a total
  * function of an id, so two drivers can only disagree about ownership by holding different
  * epochs, and the epoch is on every plan.
  */
case class ShardMap(epoch: Long, shardCount: Int) derives JsonCodec

/** A shard map that has been published but is not in force yet -- phase one of §12's two-phase
  * rebalance.
  *
  * While this is present, a driver keeps serving [[LoadPlan.shards]] but stops scheduling new
  * work for the users it is about to hand over (see [[ShardDrain]]), and finishes the ones
  * already in flight. At `drainUntilEpochMillis` the coordinator rewrites `vu_users.shard` and
  * promotes this map to the current one.
  *
  * The drain is the whole safety argument: exactly one process may ever hold a given refresh
  * token (§7.1), and modulo re-sharding moves half the population at 8 → 16. Without a drain the
  * two owners of a moved user overlap by one poll interval, which is long enough for both to
  * present the same rotating refresh token and produce a reuse detection the SUT is blameless
  * for.
  */
case class ShardMapChange(epoch: Long, shardCount: Int, drainUntilEpochMillis: Long) derives JsonCodec

/** The body of `GET /plan` (§12), polled by every driver every `coordinator.poll-interval`.
  *
  * Everything a driver needs to run the next interval and nothing about any individual virtual
  * user: the coordinator holds no per-user state, so a driver that keeps running on the last plan
  * it fetched is running on a complete plan, not a degraded one (§12: "if it dies, drivers keep
  * running on the last plan they fetched").
  *
  * @param startedAtEpochMillis
  *   the campaign's effective start, which is the anchor of every driver's arrival recurrence and
  *   of the phase timeline. `None` before the campaign starts. It moves forward by the length of
  *   a pause, so a paused campaign resumes into the phase it left rather than into wherever the
  *   wall clock has got to.
  */
case class LoadPlan(
    campaign: String,
    state: CampaignState,
    phase: Option[String],
    startedAtEpochMillis: Option[Long],
    publishedAtEpochMillis: Long,
    pollIntervalMillis: Long,
    scenarios: List[ScenarioRate],
    shards: ShardMap,
    pendingShards: Option[ShardMapChange],
) derives JsonCodec

/** The body of `POST /shards/rebalance`.
  *
  * @param drainMillis
  *   how long the drain window is. Required, with no default, because the right value is a
  *   property of the campaign rather than of the coordinator: it has to exceed the longest
  *   session a driver may still be running plus one poll interval, and a window that is too short
  *   does not fail -- it silently hands a live refresh token to a second owner.
  */
case class RebalanceRequest(shardCount: Int, drainMillis: Long) derives JsonCodec

/** The fleet's progress on one scenario.
  *
  * `achievedPerSecond` is `None` rather than 0 until two reports from the same driver can be
  * differenced: a fleet that has just started, or a coordinator that has just taken over, has no
  * measured rate at all, and a zero there reads as a fleet that has stopped generating load --
  * which is the one reading an operator would act on immediately.
  */
case class ScenarioProgress(
    scenario: PlanScenario,
    plannedPerSecond: Double,
    achievedPerSecond: Option[Double],
) derives JsonCodec

/** The body of `GET /status` (§12): achieved against planned rate, merged quantiles, the error
  * taxonomy and the population counts.
  *
  * The quantiles are merged over a recent window rather than the whole campaign -- see
  * [[CoordinatorService.statusWindow]] -- because this is the live view. `GET /report/{campaign}`
  * is the one that merges everything.
  */
case class CoordinatorStatus(
    campaign: String,
    state: CampaignState,
    phase: Option[String],
    shards: ShardMap,
    pendingShards: Option[ShardMapChange],
    drivers: List[String],
    staleDrivers: List[String],
    scenarios: List[ScenarioProgress],
    latency: List[LatencySummary],
    taxonomy: ErrorTaxonomy,
    health: CampaignHealth,
    population: Map[String, Long],
) derives JsonCodec

/** Why a request was refused, as JSON. The coordinator's refusals are all operator errors --
  * rebalancing a stopped campaign, pausing one that never started -- and the operator is the one
  * who has to read them.
  */
case class ErrorBody(error: String) derives JsonCodec
