package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.loadgen.model.{VirtualUser, VirtualUserState}
import versola.util.Secret
import zio.{Chunk, Task, ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresVirtualUserRepository(xa: TransactorZIO) extends VirtualUserRepository, StoreCodecs:

  private given DbCodec[VirtualUser] = DbCodec.derived

  override def insertAll(users: Chunk[VirtualUser]): Task[Unit] =
    if users.isEmpty then ZIO.unit
    else
      xa.transactMeasured("insert-virtual-users"):
        batchUpdate(users): user =>
          sql"""
            INSERT INTO vu_users (
              id, sut_user_id, phone, password, activity_class, platform, credential, role,
              passkey_key, passkey_cred_id, state, shard, last_seen_at
            ) VALUES (
              ${user.id}, ${user.sutUserId}, ${user.phone}, ${user.password},
              ${user.activityClass}, ${user.platform}, ${user.credential}, ${user.role},
              ${user.passkeyKey}, ${user.passkeyCredId}, ${user.state}, ${user.shard},
              ${user.lastSeenAt}
            )
          """.update
      .unit

  override def find(id: Long): Task[Option[VirtualUser]] =
    xa.connectMeasured("find-virtual-user"):
      sql"""
        SELECT id, sut_user_id, phone, password, activity_class, platform, credential, role,
               passkey_key, passkey_cred_id, state, shard, last_seen_at
        FROM vu_users WHERE id = $id
      """.query[VirtualUser].run().headOption

  /** `afterId` becomes a sentinel below every possible id rather than a nullable predicate:
    * `(? IS NULL OR id > ?)` is not sargable, and this query exists to be a range scan of
    * `vu_users_shard_idx`. Ids are dense and non-negative by construction (dev spec §6), so -1
    * is below every real one.
    */
  override def loadShardSlice(shard: Int, afterId: Option[Long], limit: Int): Task[Vector[VirtualUser]] =
    val after = afterId.getOrElse(-1L)
    xa.connectMeasured("load-virtual-user-shard-slice"):
      sql"""
        SELECT id, sut_user_id, phone, password, activity_class, platform, credential, role,
               passkey_key, passkey_cred_id, state, shard, last_seen_at
        FROM vu_users
        WHERE shard = $shard AND id > $after
        ORDER BY id
        LIMIT $limit
      """.query[VirtualUser].run()

  override def markRegistered(id: Long, sutUserId: UUID): Task[Unit] =
    val registered = VirtualUserState.Registered
    xa.connectMeasured("mark-virtual-user-registered"):
      sql"""
        UPDATE vu_users SET sut_user_id = $sutUserId, state = $registered WHERE id = $id
      """.update.run()
    .unit

  override def markBroken(id: Long): Task[Unit] =
    val broken = VirtualUserState.Broken
    xa.connectMeasured("mark-virtual-user-broken"):
      sql"UPDATE vu_users SET state = $broken WHERE id = $id".update.run()
    .unit

  override def recordPasskey(id: Long, key: Secret, credentialId: String): Task[Unit] =
    xa.connectMeasured("record-virtual-user-passkey"):
      sql"""
        UPDATE vu_users SET passkey_key = $key, passkey_cred_id = $credentialId WHERE id = $id
      """.update.run()
    .unit

  /** One JDBC batch, one round trip, rather than the `COPY` into a temp table plus
    * `UPDATE ... FROM` that dev spec §7.5 describes. At the configured batch size (500 rows,
    * every 200 ms) a batched `UPDATE ... WHERE id = ?` is a primary-key lookup per row against
    * an UNLOGGED table and is not the cost worth a temp table per flush; `COPY` earns its
    * setup at the seeder's scale (§10), not at this one.
    */
  override def touchAll(touches: Chunk[UserTouch]): Task[Unit] =
    if touches.isEmpty then ZIO.unit
    else
      xa.transactMeasured("touch-virtual-users"):
        batchUpdate(touches): touch =>
          sql"UPDATE vu_users SET last_seen_at = ${touch.lastSeenAt} WHERE id = ${touch.userId}".update
      .unit

  override def countByState: Task[Map[VirtualUserState, Long]] =
    xa.connectMeasured("count-virtual-users-by-state"):
      sql"SELECT state, count(*) FROM vu_users GROUP BY state"
        .query[(VirtualUserState, Long)].run().toMap

object PostgresVirtualUserRepository:
  def live: ZLayer[TransactorZIO, Throwable, VirtualUserRepository] =
    ZLayer.fromFunction(PostgresVirtualUserRepository(_))
