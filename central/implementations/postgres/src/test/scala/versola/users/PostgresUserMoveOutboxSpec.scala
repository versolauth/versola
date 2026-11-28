package versola.users

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.configuration.topology.{CellId, CellRecord, CellState, RegionId, TopologyRepository}
import versola.central.users.{
  AuthClient,
  CellAuthClient,
  MovePhase,
  MoveReason,
  OutboxEvent,
  UserDirectoryRecord,
  UserId,
  UserMove,
  UserOutboxProcessor,
}
import versola.util.postgres.PostgresSpec
import versola.util.{Email, SecureRandom}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The outbox and a move together, on the real tables (versolauth/versola#1051): what is queued for a user while its move is unfinished
  * waits, and is delivered to the cell the directory names once the move is over, which is the new cell. */
object PostgresUserMoveOutboxSpec extends PostgresSpec, ZIOStubs:

  private val moving = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val other = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val kz1 = CellId("kz-1")
  private val kz2 = CellId("kz-2")
  private val t0 = Instant.parse("2027-01-01T00:00:00Z")

  private def cell(id: CellId) =
    CellRecord(
      id = id,
      regionId = RegionId("almaty"),
      internalUrl = s"http://$id.internal:9003",
      mtlsUrl = None,
      trustedCertificates = Nil,
      state = CellState.Open,
      weight = 100,
      publicKeys = Nil,
    )

  private def event(id: UserId) =
    OutboxEvent.UpsertUser(userId = id, version = UUID.randomUUID(), email = Some(Email("moved@example.com")), phone = None, login = None)

  def spec = suite("a move and the outbox")(
    test("an event queued for a user that is moving waits, and is delivered to the new cell after the flip, never to the old one") {
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"TRUNCATE TABLE user_moves, user_directory, identifier_directory, user_outbox, user_outbox_dead".update.run())
        secureRandom <- SecureRandom.live.build.map(_.get[SecureRandom])
        users = PostgresUserRepository(xa, secureRandom)
        moves = PostgresUserMoveRepository(xa)
        source = stub[AuthClient]
        target = stub[AuthClient]
        topology = stub[TopologyRepository]
        _ <- topology.getCells.succeedsWith(Vector(cell(kz1), cell(kz2)))
        _ <- source.upsertUser.succeedsWith(())
        _ <- target.upsertUser.succeedsWith(())
        client = CellAuthClient(
          configured = stub[AuthClient],
          configuredCell = None,
          at = (_, id) => if id == kz1 then source else target,
          users = users,
          topology = topology,
        )
        processor <- (ZLayer.succeed(TestCentralConfig.config) ++ ZLayer.succeed[versola.central.users.UserRepository](users) ++
          ZLayer.succeed[AuthClient](client) ++ ZLayer.environment[Scope] >>> UserOutboxProcessor.live).build.map(_.get[UserOutboxProcessor])
        movingEvent = event(moving)
        _ <- users.create(UserDirectoryRecord(moving, Some(kz1)), Nil, movingEvent, UUID.randomUUID())
        otherEvent = event(other)
        _ <- users.create(UserDirectoryRecord(other, Some(kz1)), Nil, otherEvent, UUID.randomUUID())
        _ <- moves.start(
          UserMove(
            userId = moving,
            fromCell = kz1,
            toCell = kz2,
            phase = MovePhase.Leaving,
            startedAt = t0,
            updatedAt = t0,
            attempts = 0,
            error = None,
            reason = MoveReason.Admin,
            notBefore = t0,
            departedAt = None,
            moveId = UUID.fromString("00000000-0000-7000-8000-0000000000a1"),
            digest = None,
          ),
        )
        _ <- processor.flush()
        whileLeaving = (source.upsertUser.calls.map(_._1), target.upsertUser.calls.map(_._1))
        _ <- moves.advance(moving, MovePhase.Leaving, MovePhase.Exported, t0, t0)
        _ <- moves.advance(moving, MovePhase.Exported, MovePhase.Arrived, t0, t0)
        _ <- moves.flip(moving, t0, t0)
        _ <- processor.flush()
        whileFlipped = (source.upsertUser.calls.map(_._1), target.upsertUser.calls.map(_._1))
        pendingWhileFlipped <- users.pendingEvents(moving)
        _ <- moves.advance(moving, MovePhase.Flipped, MovePhase.Done, t0, t0)
        _ <- processor.flush()
        pendingAfter <- users.pendingEvents(moving)
      yield assertTrue(
        // the user of no move is delivered to its cell all along; the moving one's event waits
        whileLeaving == ((List(other), List.empty[UserId])),
        whileFlipped == ((List(other), List.empty[UserId])),
        pendingWhileFlipped == Vector(movingEvent),
        // once the move is done the event goes to the cell the directory names, which is the target
        source.upsertUser.calls.map(_._1) == List(other),
        target.upsertUser.calls.map(_._1) == List(moving),
        pendingAfter.isEmpty,
      )
    },
    test("an event queued behind a move that failed is delivered to the old cell, which serves the user again") {
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"TRUNCATE TABLE user_moves, user_directory, identifier_directory, user_outbox, user_outbox_dead".update.run())
        secureRandom <- SecureRandom.live.build.map(_.get[SecureRandom])
        users = PostgresUserRepository(xa, secureRandom)
        moves = PostgresUserMoveRepository(xa)
        source = stub[AuthClient]
        target = stub[AuthClient]
        topology = stub[TopologyRepository]
        _ <- topology.getCells.succeedsWith(Vector(cell(kz1), cell(kz2)))
        _ <- source.upsertUser.succeedsWith(())
        client = CellAuthClient(
          configured = stub[AuthClient],
          configuredCell = None,
          at = (_, id) => if id == kz1 then source else target,
          users = users,
          topology = topology,
        )
        processor <- (ZLayer.succeed(TestCentralConfig.config) ++ ZLayer.succeed[versola.central.users.UserRepository](users) ++
          ZLayer.succeed[AuthClient](client) ++ ZLayer.environment[Scope] >>> UserOutboxProcessor.live).build.map(_.get[UserOutboxProcessor])
        _ <- users.create(UserDirectoryRecord(moving, Some(kz1)), Nil, event(moving), UUID.randomUUID())
        _ <- moves.start(
          UserMove(
            userId = moving,
            fromCell = kz1,
            toCell = kz2,
            phase = MovePhase.Leaving,
            startedAt = t0,
            updatedAt = t0,
            attempts = 0,
            error = None,
            reason = MoveReason.Admin,
            notBefore = t0,
            departedAt = None,
            moveId = UUID.fromString("00000000-0000-7000-8000-0000000000a1"),
            digest = None,
          ),
        )
        _ <- processor.flush()
        whileMoving = source.upsertUser.calls.size
        _ <- moves.beginAbandon(moving, MovePhase.Leaving, "depart_failed", t0, t0)
        _ <- moves.abandoned(moving, t0)
        _ <- processor.flush()
      yield assertTrue(whileMoving == 0, source.upsertUser.calls.map(_._1) == List(moving), target.upsertUser.calls.isEmpty)
    },
  ) @@ TestAspect.sequential
