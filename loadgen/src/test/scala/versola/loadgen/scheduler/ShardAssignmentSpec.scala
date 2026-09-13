package versola.loadgen.scheduler

import versola.loadgen.config.ShardConfig
import zio.test.*

/** §13's "shard assignment is stable across restarts".
  *
  * "Stable" is asserted three ways, because the one that matters cannot be observed from inside a
  * single test run:
  *
  *   1. **Checked-in golden values.** A restart, or a second process, re-derives the mapping from
  *      nothing but the id and the count; the only way to state that here is to compare against
  *      literals that live in the source and therefore survive both. If [[ShardAssignment]] ever
  *      acquires a seed, a `hashCode`, or a JDK-version-dependent hash, these literals break.
  *   2. **Order independence.** The mapping is recomputed over a shuffled traversal (seeded, so
  *      the shuffle is itself reproducible) and must agree -- no accumulated state.
  *   3. **Partition.** Over a contiguous id range every id is owned by exactly one shard, which
  *      is the property §7.1 actually needs: it is what guarantees that no two drivers can ever
  *      hold the same refresh token, and that no user is stranded and never driven.
  */
object ShardAssignmentSpec extends ZIOSpecDefault:

  def spec = suite("ShardAssignment")(
    suite("stability")(
      test("golden mapping: id % count, checked in so a restart cannot disagree with it") {
        val ids = List(0L, 1L, 7L, 8L, 12345L, 999999L, 10_000_000L, 8_589_934_592L)
        assertTrue(
          ids.map(ShardAssignment.shardOf(_, 8)) == List(0, 1, 7, 0, 1, 7, 0, 0),
          ids.map(ShardAssignment.shardOf(_, 7)) == List(0, 1, 0, 1, 4, 0, 3, 1),
          ids.map(ShardAssignment.shardOf(_, 64)) == List(0, 1, 7, 8, 57, 63, 0, 0),
          (0L until 21L).map(ShardAssignment.shardOf(_, 7)).toList ==
            List(0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 5, 6),
        )
      },
      test("does not depend on the order ids are presented in, or on how often") {
        val ids = (0L until 50_000L).toVector
        val inOrder = ids.map(id => id -> ShardAssignment.shardOf(id, 8)).toMap
        val shuffled = scala.util.Random(20260910L).shuffle(ids)
        assertTrue(
          shuffled.forall(id => ShardAssignment.shardOf(id, 8) == inOrder(id)),
          shuffled.forall(id => ShardAssignment.shardOf(id, 8) == ShardAssignment.shardOf(id, 8)),
        )
      },
      test("a single shard's ownership is unchanged by the shard's own index moving") {
        // Shard 3 of 8 owns exactly the same ids whether it is this pod's index or another's --
        // ownership is a property of the id, not of the process asking.
        val shard = ShardConfig(index = 3, count = 8)
        val owned = (0L until 10_000L).filter(ShardAssignment.isOwnedBy(_, shard))
        assertTrue(owned.forall(id => ShardAssignment.shardOf(id, 8) == 3), owned.size == 1_250)
      },
    ),
    suite("partition")(
      test("every id is owned by exactly one shard of the map") {
        val shards = (0 until 8).map(index => ShardConfig(index = index, count = 8))
        val owners = (0L until 20_000L).map(id => shards.count(ShardAssignment.isOwnedBy(id, _)))
        assertTrue(owners.forall(_ == 1))
      },
      test("each shard of eight owns an eighth of a dense range") {
        val counts = (0 until 8).map: index =>
          (0L until 1_000_000L).count(ShardAssignment.isOwnedBy(_, ShardConfig(index = index, count = 8)))
        assertTrue(counts.forall(_ == 125_000))
      },
      test("a negative id lands in range rather than on a shard no driver claims") {
        assertTrue((-8L to -1L).forall(id => ShardAssignment.shardOf(id, 8) >= 0))
      },
    ),
    suite("range scan")(
      test("firstOwnedIdAtOrAfter steps the range instead of testing every id") {
        val shard = ShardConfig(index = 3, count = 8)
        assertTrue(
          ShardAssignment.firstOwnedIdAtOrAfter(100L, shard) == 107L,
          ShardAssignment.firstOwnedIdAtOrAfter(107L, shard) == 107L,
          (0L until 1_000L).forall: from =>
            val first = ShardAssignment.firstOwnedIdAtOrAfter(from, shard)
            first >= from && first - from < shard.count && ShardAssignment.isOwnedBy(first, shard),
        )
      },
    ),
    suite("validation")(
      test("accepts a well-formed shard config") {
        assertTrue(ShardAssignment.validate(ShardConfig(index = 7, count = 8)) == Right(ShardConfig(index = 7, count = 8)))
      },
      test("rejects the configs that would silently generate no load") {
        assertTrue(
          ShardAssignment.validate(ShardConfig(index = 8, count = 8)).isLeft,
          ShardAssignment.validate(ShardConfig(index = -1, count = 8)).isLeft,
          ShardAssignment.validate(ShardConfig(index = 0, count = 0)).isLeft,
          scala.util.Try(ShardAssignment.shardOf(1L, 0)).isFailure,
        )
      },
    ),
  )
