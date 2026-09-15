package versola.loadgen.scenario

import versola.loadgen.config.ShardConfig
import zio.UIO
import zio.ZIO

import java.util.concurrent.ThreadLocalRandom

/** Mints `vu_sessions.id` for the sessions this driver creates.
  *
  * The column is a bare `BIGINT PRIMARY KEY` with no sequence and no default (migration V0002,
  * versolauth/versola#267), so the value is the driver's to choose -- and eight drivers choose
  * concurrently, across restarts, against rows a previous process left behind. Two constraints
  * follow: ids must be disjoint *between* drivers, and a driver must not reuse one of its own.
  *
  * Both are met by generating in this shard's residue class: `id ≡ shard.index (mod shard.count)`
  * makes another driver's id unreachable by construction, the same arithmetic `shard = id %
  * count` uses for users (§7.1), and the multiplier is drawn uniformly from the whole remaining
  * range rather than counted up from a base. Counting up is what a restart breaks: a fresh
  * process has no memory of where the last one stopped, and reading the table for a maximum
  * turns driver startup into a scan of a table the campaign is actively writing.
  *
  * The residue class holds ~1.15e18 values at eight shards, so a driver holding a million live
  * sessions collides with probability ~4e-7 -- and a collision is not silent: the insert fails on
  * the primary key and the session is abandoned, costing one login.
  *
  * `ThreadLocalRandom` rather than [[versola.loadgen.scheduler.RandomSource]], which is seeded so
  * that a campaign can be replayed: a session id is bookkeeping, it changes nothing about the
  * load generated, and a seeded generator here would be shared mutable state on the hot path for
  * no gain.
  */
final class SessionIds private (shard: ShardConfig, bound: Long):
  def next: UIO[Long] =
    ZIO.succeed(shard.index.toLong + shard.count.toLong * ThreadLocalRandom.current().nextLong(bound))

object SessionIds:
  def make(shard: ShardConfig): SessionIds =
    require(shard.count > 0, s"shard count must be positive, got ${shard.count}")
    require(shard.index >= 0 && shard.index < shard.count, s"shard index ${shard.index} out of range for count ${shard.count}")
    SessionIds(shard, (Long.MaxValue - shard.index) / shard.count)
