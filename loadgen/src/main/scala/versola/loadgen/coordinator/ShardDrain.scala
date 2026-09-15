package versola.loadgen.coordinator

import versola.loadgen.scheduler.ShardAssignment

/** The driver's side of §12's drain protocol, stated once here rather than in each driver.
  *
  * Both sides of a rebalance are computable from an id alone, which is the property modulo
  * sharding was chosen for (versolauth/versola#267): a driver decides what to drain without a
  * range table, and without asking the coordinator about individual users -- which it could not
  * answer anyway, holding no per-user state.
  */
object ShardDrain:

  /** Whether this driver owns `id` under the map currently in force. */
  def owns(id: Long, shardIndex: Int, shards: ShardMap): Boolean =
    ShardAssignment.shardOf(id, shards.shardCount) == shardIndex

  /** Whether `id` is on its way out of this driver: owned now, and owned by somebody else once
    * the published map takes effect.
    *
    * A user whose shard is unchanged by the rebalance is not outgoing even though the bulk
    * `UPDATE` rewrites its `shard` column -- it rewrites it to the same value. That is what makes
    * the re-shard safe to run while the campaign is still generating load: the only rows whose
    * ownership actually moves are the ones every driver stopped scheduling when the drain was
    * published.
    */
  def isOutgoing(id: Long, shardIndex: Int, shards: ShardMap, pending: Option[ShardMapChange]): Boolean =
    pending.exists: change =>
      owns(id, shardIndex, shards) && ShardAssignment.shardOf(id, change.shardCount) != shardIndex

  /** Whether this driver may schedule new work for `id`.
    *
    * In-flight work is deliberately not this function's business: §12 has drivers *finish* their
    * in-flight users and stop *scheduling* the outgoing ones. Interrupting a session mid-rotation
    * to meet a drain deadline is the one thing that would produce the reuse detection the drain
    * exists to prevent.
    */
  def schedulable(id: Long, shardIndex: Int, plan: LoadPlan): Boolean =
    owns(id, shardIndex, plan.shards) && !isOutgoing(id, shardIndex, plan.shards, plan.pendingShards)
