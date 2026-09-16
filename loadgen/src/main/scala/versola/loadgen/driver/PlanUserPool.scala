package versola.loadgen.driver

import versola.loadgen.coordinator.{LoadPlan, ShardDrain}
import versola.loadgen.model.VirtualUser
import versola.loadgen.scenario.UserPool
import zio.{Ref, Task, ZIO}

/** [[UserPool]] with §12's drain applied: a user this driver is about to hand over is skipped
  * rather than scheduled.
  *
  * The filter is here rather than in the query because the pool walks `(shard, id)` in index
  * order and the drain is a predicate on the id under *two* shard maps at once -- the one in
  * force and the one published. Pushing that into SQL would be a modulo expression the index
  * cannot serve, on the one query that runs thousands of times a second.
  *
  * A skipped user costs the arrival, not the campaign: [[UserPool]] hands out the next id on the
  * following call, so a drain that moves half the population halves this driver's yield per call
  * for the length of the drain window and no longer. `attempts` bounds that: past it the arrival
  * is dropped, because a pool that scanned its whole slice looking for a schedulable user would
  * turn a drain into an unbounded stall on the dispatcher fiber.
  */
final class PlanUserPool(delegate: UserPool, shardIndex: Int, plan: Ref[LoadPlan], attempts: Int) extends UserPool:

  override def next: Task[Option[VirtualUser]] =
    plan.get.flatMap(current => attempt(current, attempts))

  private def attempt(current: LoadPlan, remaining: Int): Task[Option[VirtualUser]] =
    if remaining <= 0 then ZIO.none
    else
      delegate.next.flatMap:
        case None => ZIO.none
        case Some(user) if ShardDrain.schedulable(user.id, shardIndex, current) => ZIO.some(user)
        case Some(_) => attempt(current, remaining - 1)

object PlanUserPool:

  /** One page of the pool's own keyset window. A drain at 8 -> 16 shards leaves half the slice
    * schedulable, so the chance of a page of misses is (1/2)^1000; the bound exists for the
    * degenerate rebalance that moves nearly everything, where it turns an endless scan into a
    * dropped arrival that the gap between `loadgen_arrivals_total` and the flows makes visible.
    */
  val attempts: Int = UserPool.pageSize

  def of(delegate: UserPool, shardIndex: Int, plan: Ref[LoadPlan]): PlanUserPool =
    PlanUserPool(delegate, shardIndex, plan, attempts)
