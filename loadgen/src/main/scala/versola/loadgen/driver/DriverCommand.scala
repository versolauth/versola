package versola.loadgen.driver

import versola.loadgen.coordinator.{CampaignState, LoadPlan}

/** The facts about a plan that the running loop is built against, and that therefore cannot
  * change under it.
  *
  * `startedAt` anchors the arrival recurrence and the phase timeline, and it moves forward by the
  * length of a pause (§12) -- so a resumed campaign is a different generation, not a continuation.
  * `shardCount` sizes the driver's share of every published rate (`λ/shardCount`) and the residue
  * class `SessionIds` mints into, so a promoted shard map is one too.
  *
  * `epoch` is carried alongside `shardCount` rather than derived from it because the coordinator
  * never reuses an epoch: two maps with the same width are still two maps, and the driver reports
  * the epoch it is running so that a fleet that is half-way through a promotion is visible.
  */
final case class RunGeneration(startedAtEpochMillis: Long, shardCount: Int, epoch: Long)

/** What the driver should be doing, given the plan it just polled.
  *
  * Pure, and separate from the loop it commands, because this is the whole of the driver's
  * lifecycle: every other part of the process is either wiring done once at boot or a session the
  * scenario engine owns. A decision table that can be read and tested on its own is the
  * difference between "the driver did not generate load" being diagnosable and being a bisect.
  */
enum DriverCommand:
  /** No load. The campaign has not started, or is paused -- both of which a driver leaves by
    * polling again, so nothing is torn down beyond the loop itself.
    */
  case Idle

  case Run(generation: RunGeneration)

  /** Terminal. `CampaignState.Stopped` cannot be left through the coordinator's API, so a driver
    * that kept polling would poll a plan that can never change again.
    */
  case Stop

object DriverCommand:

  def of(plan: LoadPlan): DriverCommand =
    plan.state match
      case CampaignState.Stopped => DriverCommand.Stop
      case CampaignState.Idle | CampaignState.Paused => DriverCommand.Idle
      // A running campaign with no start instant is not a state the coordinator publishes --
      // `CampaignControl.start` sets both together. Treated as idle rather than as an error
      // because the driver's only honest response to a plan it cannot schedule against is to
      // generate nothing and poll again.
      case CampaignState.Running =>
        plan.startedAtEpochMillis match
          case None => DriverCommand.Idle
          case Some(startedAt) =>
            DriverCommand.Run(RunGeneration(startedAt, plan.shards.shardCount, plan.shards.epoch))

  /** Whether a loop already running under `current` has to be torn down and rebuilt for `next`.
    *
    * Restarting is not free -- it interrupts every in-flight session -- but the alternative is a
    * loop scheduling against an anchor or a shard width the plan no longer states, which produces
    * load nobody planned and, at a changed shard width, users two drivers both believe they own.
    * The drain protocol (§12) is what makes the restart safe to take at a promotion: by the time
    * the epoch changes, the moved users have already stopped being scheduled.
    */
  def restartRequired(current: RunGeneration, next: RunGeneration): Boolean = current != next
