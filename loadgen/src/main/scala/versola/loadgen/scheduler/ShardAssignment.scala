package versola.loadgen.scheduler

import versola.loadgen.config.ShardConfig

/** Ownership of virtual users by drivers: `shard = id % shard.count`
  * (versola-loadgen-dev-spec.md §7.1, over the dense `vu_users.id` of §6).
  *
  * The whole safety argument of the emulator rests on this being a *total function of the id
  * alone*: exactly one process may ever hold a given refresh token (§7.1, design doc §6.3), and
  * that is guaranteed only if two processes -- or the same process before and after a restart --
  * cannot disagree about who owns an id. So there is deliberately no hashing here, no seed, no
  * `hashCode`, no `Random`, and no state: `id % count` is stable by construction, which is a
  * stronger claim than "we tested it and it was stable" (see `ShardAssignmentSpec`).
  *
  * Note the divergence from design doc §6.2, which says the coordinator assigns ranges "by
  * consistent hash", and §6.2's later `hash(user_id) % shards`. The dev spec's §7.1 modulus over
  * a dense id wins, and for a stated reason: §6 chose a dense `BIGINT` id over a UUID precisely
  * so that a driver loads its slice with a range scan on `(shard, id)` rather than a hash filter,
  * and so that re-sharding is a predictable range move. Hashing the id would throw that away.
  */
object ShardAssignment:
  def shardOf(id: Long, shardCount: Int): Int =
    require(shardCount > 0, s"shard count must be positive, got $shardCount")
    // floorMod rather than `%` so a negative id (which the seeder should never produce, but
    // which a hand-written fixture row can) maps into range instead of to a negative shard that
    // no driver would ever claim -- silently stranding the user.
    math.floorMod(id, shardCount.toLong).toInt

  def isOwnedBy(id: Long, shard: ShardConfig): Boolean =
    shardOf(id, shard.count) == shard.index

  /** Lowest id `>= from` that this shard owns. The driver's active-window query (design doc
    * §6.2) is a range scan; this is what lets it step the range rather than test every id.
    */
  def firstOwnedIdAtOrAfter(from: Long, shard: ShardConfig): Long =
    require(shard.count > 0, s"shard count must be positive, got ${shard.count}")
    require(shard.index >= 0 && shard.index < shard.count, s"shard index ${shard.index} out of range for count ${shard.count}")
    from + math.floorMod(shard.index.toLong - from, shard.count.toLong)

  /** Rejects the two shard configurations that produce a silently under-loaded campaign rather
    * than an error: an index outside the map (the driver owns nothing and generates no load) and
    * a non-positive count. Role dispatch calls this once at boot; it is not on any hot path.
    */
  def validate(shard: ShardConfig): Either[String, ShardConfig] =
    if shard.count <= 0 then Left(s"shard.count must be positive, got ${shard.count}")
    else if shard.index < 0 || shard.index >= shard.count then
      Left(s"shard.index must be in [0, ${shard.count}), got ${shard.index}")
    else Right(shard)
