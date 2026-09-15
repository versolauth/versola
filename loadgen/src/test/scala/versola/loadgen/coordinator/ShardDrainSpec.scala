package versola.loadgen.coordinator

import zio.test.*

/** The driver's side of the drain, against the property the whole safety argument rests on:
  * during a drain, every id is schedulable by at most one shard index, and a user that is moving
  * is schedulable by none.
  */
object ShardDrainSpec extends ZIOSpecDefault:

  private val eight = ShardMap(epoch = 0L, shardCount = 8)

  private val toSixteen = Some(ShardMapChange(epoch = 1L, shardCount = 16, drainUntilEpochMillis = 0L))

  private val ids = 1L to 4_000L

  def spec = suite("ShardDrain")(
    test("ownership under the map in force is the modulus, and nothing else") {
      assertTrue(
        ids.count(id => ShardDrain.owns(id, 3, eight)) == 500,
        ids.forall(id => ShardDrain.owns(id, (id % 8).toInt, eight)),
      )
    },
    test("exactly the users whose shard changes are outgoing") {
      val outgoing = ids.filter(id => ShardDrain.isOutgoing(id, 3, eight, toSixteen))
      assertTrue(
        // 8 → 16 splits every old shard in two: half of shard 3's users stay (id % 16 == 3), half
        // move to shard 11. A row that stays is not outgoing even though the bulk UPDATE rewrites
        // its column -- to the same value.
        outgoing.forall(id => id % 8 == 3 && id % 16 == 11),
        outgoing.size == 250,
      )
    },
    test("a user this driver does not own is never its outgoing user") {
      assertTrue(ids.filter(id => id % 8 != 3).forall(id => !ShardDrain.isOutgoing(id, 3, eight, toSixteen)))
    },
    test("with no rebalance published, nothing is outgoing") {
      assertTrue(ids.forall(id => !ShardDrain.isOutgoing(id, 3, eight, None)))
    },
    test("during a drain every id is schedulable by at most one shard, and moving ids by none") {
      val draining = plan(eight, toSixteen)
      val claims = ids.map(id => id -> (0 until 8).count(index => ShardDrain.schedulable(id, index, draining)))
      assertTrue(
        claims.forall((_, owners) => owners <= 1),
        claims.filter((id, _) => id % 8 != id % 16).forall((_, owners) => owners == 0),
        claims.filter((id, _) => id % 8 == id % 16).forall((_, owners) => owners == 1),
      )
    },
    test("once the map is in force every id is schedulable by exactly one shard again") {
      val settled = plan(ShardMap(epoch = 1L, shardCount = 16), None)
      assertTrue(ids.forall(id => (0 until 16).count(index => ShardDrain.schedulable(id, index, settled)) == 1))
    },
  )

  private def plan(shards: ShardMap, pending: Option[ShardMapChange]): LoadPlan =
    LoadPlan(
      campaign = "c3-10m-steady",
      state = CampaignState.Running,
      phase = Some("steady"),
      startedAtEpochMillis = Some(0L),
      publishedAtEpochMillis = 0L,
      pollIntervalMillis = 10_000L,
      scenarios = Nil,
      shards = shards,
      pendingShards = pending,
    )
