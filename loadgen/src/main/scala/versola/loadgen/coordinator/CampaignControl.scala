package versola.loadgen.coordinator

import java.time.{Duration as JavaDuration, Instant}

/** Everything the coordinator remembers, and deliberately nothing else: the campaign's lifecycle,
  * its effective start, and the shard map (versola-loadgen-dev-spec.md §12, "the coordinator
  * holds no per-user state").
  *
  * All of it is a pure function of the commands the operator issued, which is what makes the
  * standby's takeover cheap and the transitions testable without a clock, a database or a server.
  * It is also all of it in memory: a coordinator restart loses the campaign's start instant and
  * any drain in flight, and that trade is the subject of [[CampaignControl]]'s own doc.
  *
  * @param startedAt
  *   the *effective* start, moved forward by the length of every pause. The phase timeline and
  *   every driver's arrival recurrence are anchored on it, so a campaign paused for an hour
  *   resumes into the phase it left rather than an hour further along a plan nothing executed.
  */
final case class CampaignControl(
    state: CampaignState,
    startedAt: Option[Instant],
    pausedAt: Option[Instant],
    shards: ShardMap,
    pendingShards: Option[ShardMapChange],
):

  def start(now: Instant): Either[String, CampaignControl] = state match
    case CampaignState.Idle =>
      Right(copy(state = CampaignState.Running, startedAt = Some(now), pausedAt = None))
    case CampaignState.Paused =>
      // The pause is subtracted from the campaign clock rather than from the phase offsets: one
      // instant to shift, and the drivers re-anchor on it from the plan's own
      // `startedAtEpochMillis` with no notion of how many pauses there have been.
      val resumed = for
        anchor <- startedAt
        paused <- pausedAt
      yield anchor.plus(JavaDuration.between(paused, now))
      Right(copy(state = CampaignState.Running, startedAt = resumed.orElse(Some(now)), pausedAt = None))
    case CampaignState.Running => Right(this)
    case CampaignState.Stopped => Left("a stopped campaign cannot be restarted; start a new one")

  def pause(now: Instant): Either[String, CampaignControl] = state match
    case CampaignState.Running => Right(copy(state = CampaignState.Paused, pausedAt = Some(now)))
    case CampaignState.Paused => Right(this)
    case CampaignState.Idle => Left("the campaign has not started")
    case CampaignState.Stopped => Left("the campaign is already stopped")

  def stop: Either[String, CampaignControl] = state match
    case CampaignState.Stopped => Right(this)
    case _ => Right(copy(state = CampaignState.Stopped, pausedAt = None))

  /** Phase one of §12's rebalance: publish the new map with a drain deadline.
    *
    * Refused while a drain is already in flight. Two overlapping maps would mean a user could be
    * outgoing under one and incoming under the other, and the bulk `UPDATE` of the first would
    * then run against the drain of the second -- so the second map's moved users would change
    * owner with nothing drained.
    */
  def publishRebalance(shardCount: Int, drainUntil: Instant, now: Instant): Either[String, CampaignControl] =
    if shardCount <= 0 then Left(s"shard count must be positive, got $shardCount")
    // `vu_users.shard` and `vu_sessions.shard` are SMALLINT, and a map this wide is not caught
    // until the bulk UPDATE -- which runs after the drain window has elapsed, is retried forever
    // by `settle`, and leaves the moved users drained meanwhile. Here the operator is still on
    // the other end of the request and can be told.
    else if shardCount > ShardMap.maxShardCount then
      Left(s"shard count must be at most ${ShardMap.maxShardCount}, got $shardCount")
    else if state == CampaignState.Stopped then Left("the campaign is stopped")
    else if pendingShards.nonEmpty then Left("a rebalance is already draining")
    else if shardCount == shards.shardCount then Left(s"the shard map is already at $shardCount shards")
    else if !drainUntil.isAfter(now) then Left(s"the drain deadline $drainUntil is not in the future")
    else
      Right(
        copy(pendingShards = Some(ShardMapChange(shards.epoch + 1L, shardCount, drainUntil.toEpochMilli))),
      )

  /** The published map, once its drain window has elapsed. `None` while there is nothing to
    * promote, which is the ordinary case on every tick.
    */
  def dueRebalance(now: Instant): Option[ShardMapChange] =
    pendingShards.filter(change => now.toEpochMilli >= change.drainUntilEpochMillis)

  /** Phase two: the published map becomes the map in force.
    *
    * Only ever called after `vu_users.shard` has been rewritten, and that ordering is the drain
    * protocol's other half. Promoting first would publish ownership that the rows do not yet
    * agree with, and a driver's slice query is a range scan on `(shard, id)` -- it would read an
    * empty slice and generate no load, silently, for as long as the `UPDATE` took.
    */
  def commitRebalance(change: ShardMapChange): CampaignControl =
    if !pendingShards.contains(change) then this
    else copy(shards = ShardMap(change.epoch, change.shardCount), pendingShards = None)

object CampaignControl:
  def initial(shardCount: Int): CampaignControl =
    CampaignControl(
      state = CampaignState.Idle,
      startedAt = None,
      pausedAt = None,
      // Epoch 0 is the map the campaign was configured with, not one this coordinator invented:
      // the seeder already wrote `vu_users.shard` from the same count (§6), so nothing has to be
      // rewritten to make it true.
      shards = ShardMap(epoch = 0L, shardCount = shardCount),
      pendingShards = None,
    )
