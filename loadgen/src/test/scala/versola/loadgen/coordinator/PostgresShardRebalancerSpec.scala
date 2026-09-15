package versola.loadgen.coordinator

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.model.{DeviceSession, SessionKind, VirtualUserState}
import versola.loadgen.protocol.{RefreshToken, SsoSession}
import versola.loadgen.scheduler.ShardAssignment
import versola.loadgen.store.{
  LoadgenPostgresSpec,
  PostgresDeviceSessionRepository,
  PostgresVirtualUserRepository,
  VirtualUserRepository,
}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

final case class RebalancerEnv(
    rebalancer: ShardRebalancer,
    users: VirtualUserRepository,
    sessions: PostgresDeviceSessionRepository,
    xa: TransactorZIO,
)

/** Phase two of the rebalance against a real Postgres.
  *
  * The property under test is the one the drain protocol assumes and cannot check for itself:
  * after the bulk `UPDATE`, every row's `shard` is exactly `ShardAssignment.shardOf(id, count)`
  * -- in both tables. A `vu_sessions` row left on its user's old shard is a live refresh token
  * owned by a driver that has drained the user, which is the overlap the drain exists to prevent
  * and which no amount of in-memory testing would find.
  */
object PostgresShardRebalancerSpec extends LoadgenPostgresSpec, DatabaseSpecBase[RebalancerEnv]:

  private val now = Instant.parse("2026-09-15T08:00:00Z")

  private val population = 1L to 200L

  private def session(id: Long, shard: Int): DeviceSession =
    DeviceSession(
      id = id,
      userId = id,
      kind = SessionKind.MobileToken,
      clientId = "mobile-otp",
      refreshToken = Option(RefreshToken(s"refresh-$id")),
      edgeCookie = None,
      ssoSession = Some(SsoSession(s"sso-$id")),
      accessExpiresAt = Some(now.plusSeconds(900)),
      refreshExpiresAt = Some(now.plusSeconds(2_592_000)),
      acr = Some("L1"),
      authTime = Some(now.minusSeconds(600)),
      generation = 0,
      refreshGeneration = 0,
      shard = shard,
    )

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO]: xa =>
        RebalancerEnv(
          rebalancer = PostgresShardRebalancer(xa),
          users = PostgresVirtualUserRepository(xa),
          sessions = PostgresDeviceSessionRepository(xa),
          xa = xa,
        )

  override def beforeEach(env: RebalancerEnv) =
    for
      _ <- ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE vu_users".update.run()).unit)
      _ <- ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE vu_sessions".update.run()).unit)
      _ <- env.users.insertAll(
        Chunk.fromIterable(
          population.map: id =>
            CoordinatorFixture
              .user(id, VirtualUserState.Registered)
              .copy(shard = ShardAssignment.shardOf(id, 8)),
        ),
      )
      _ <- ZIO.foreachDiscard(population)(id => env.sessions.insert(session(id, ShardAssignment.shardOf(id, 8))))
    yield ()

  private def userShards(xa: TransactorZIO): Task[Vector[(Long, Int)]] =
    xa.connect(sql"SELECT id, shard FROM vu_users ORDER BY id".query[(Long, Int)].run())

  private def sessionShards(xa: TransactorZIO): Task[Vector[(Long, Int)]] =
    xa.connect(sql"SELECT user_id, shard FROM vu_sessions ORDER BY user_id".query[(Long, Int)].run())

  override def testCases(env: RebalancerEnv) = List(
    test("re-sharding 8 to 16 lands every user and every session on the modulus of the new count") {
      for
        moved <- env.rebalancer.reassign(16)
        users <- userShards(env.xa)
        sessions <- sessionShards(env.xa)
      yield assertTrue(
        moved == population.size.toLong,
        users.forall((id, shard) => shard == ShardAssignment.shardOf(id, 16)),
        // Nearly half the population changes owner at 8 → 16 -- every id whose `% 16` lands in
        // 8..15, which is 97 of these 200 -- and that is what makes this a bulk UPDATE rather
        // than a range move.
        users.count((id, shard) => shard != ShardAssignment.shardOf(id, 8)) == 97,
        sessions.forall((userId, shard) => shard == ShardAssignment.shardOf(userId, 16)),
      )
    },
    test("shrinking the map is the same statement, and leaves the two tables agreeing") {
      for
        _ <- env.rebalancer.reassign(16)
        _ <- env.rebalancer.reassign(4)
        users <- userShards(env.xa)
        sessions <- sessionShards(env.xa)
      yield assertTrue(
        users.forall((id, shard) => shard == ShardAssignment.shardOf(id, 4)),
        users.map(_._2).toSet == Set(0, 1, 2, 3),
        sessions.map((userId, shard) => shard) == users.map(_._2),
      )
    },
    test("a re-shard onto one shard is legal: the whole population lands on driver 0") {
      for
        _ <- env.rebalancer.reassign(1)
        users <- userShards(env.xa)
      yield assertTrue(users.forall((_, shard) => shard == 0))
    },
    test("a non-positive count is refused before it truncates the map to nothing") {
      for
        zero <- env.rebalancer.reassign(0).either
        negative <- env.rebalancer.reassign(-8).either
        users <- userShards(env.xa)
      yield assertTrue(
        zero.isLeft,
        negative.isLeft,
        users.forall((id, shard) => shard == ShardAssignment.shardOf(id, 8)),
      )
    },
    test("the shard column keeps agreeing with ShardAssignment for a negative id") {
      // Not a row the seeder can produce, but the column is the denormalisation of a `floorMod`
      // and Postgres' own `%` is not one -- so a fixture row is the only way to state that the
      // two cannot drift.
      for
        _ <- env.users.insertAll(Chunk(CoordinatorFixture.user(-3L, VirtualUserState.Registered)))
        _ <- env.rebalancer.reassign(8)
        users <- userShards(env.xa)
      yield assertTrue(users.forall((id, shard) => shard == ShardAssignment.shardOf(id, 8)))
    },
  )
