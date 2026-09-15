package versola.loadgen.coordinator

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.BasicCodecs
import zio.{Task, ZIO}

/** Phase two of the rebalance: rewriting who owns whom.
  *
  * Its own narrow port rather than a method on `VirtualUserRepository`, for two reasons. This is
  * the one write in the emulator that is not about a single virtual user, so it does not belong on
  * a trait every driver holds a live instance of -- a driver has no business being able to
  * re-shard the population. And it lets the drain protocol be tested against a fake without a
  * database, which is where all of its interesting behaviour is.
  */
trait ShardRebalancer:

  /** Re-assigns every virtual user and every session to `shardCount` shards.
    *
    * @return rows rewritten in `vu_users`, which is the campaign's population -- logged, because
    *         the duration of this statement is what a too-short drain window is measured against.
    */
  def reassign(shardCount: Int): Task[Long]

/** The bulk `UPDATE` of dev spec §6 and versolauth/versola#267: `shard = id % shardCount` over
  * the whole table, not a range move.
  *
  * 8 → 16 shards moves half the rows whatever the assignment function is, so there is no cheap
  * version of this operation; what makes it affordable is that it is legal only behind the drain
  * protocol, with the moved users idle. Modulo is kept because the seeder, every driver and the
  * coordinator can each compute ownership from an id alone, with no map to distribute.
  *
  * Both tables in one transaction. `vu_sessions.shard` denormalises the *user's* shard -- it is
  * what makes `vu_sessions_shard_idx (shard, refresh_expires_at)` a driver's own range scan -- so
  * a commit that moved the users and not their sessions would leave every moved session owned by
  * the previous driver, which is precisely the overlap the drain exists to prevent.
  */
final class PostgresShardRebalancer(xa: TransactorZIO) extends ShardRebalancer, BasicCodecs:

  override def reassign(shardCount: Int): Task[Long] =
    ZIO
      .fail(IllegalArgumentException(s"shard count must be positive, got $shardCount"))
      .when(shardCount <= 0)
      .flatMap: _ =>
        xa.transactMeasured("reassign-shards"):
          // `((id % n) + n) % n`, not `id % n`: Postgres' `%` follows the sign of the dividend,
          // while `ShardAssignment.shardOf` is a `floorMod`. Ids are dense and positive by
          // construction, so the two agree today -- but the column is the denormalisation of that
          // function, and a hand-written fixture row with a negative id would otherwise be
          // assigned a shard no driver claims here and a valid one in the drivers.
          val sessions = sql"UPDATE vu_sessions SET shard = ((user_id % $shardCount) + $shardCount) % $shardCount".update
            .run()
          val users = sql"UPDATE vu_users SET shard = ((id % $shardCount) + $shardCount) % $shardCount".update.run()
          (users.toLong, sessions.toLong)
        .flatMap: (users, sessions) =>
          ZIO
            .logInfo(s"Re-sharded $users virtual users and $sessions sessions onto $shardCount shards")
            .as(users)
