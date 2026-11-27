package versola.central.users

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.topology.{
  CellId,
  CellRecord,
  CellState,
  PartitionId,
  PartitionRecord,
  RegionId,
  RegionRecord,
  TopologyRepository,
}
import versola.util.SecureRandom
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Whether a move of a user may begin (versolauth/versola#1051): every refusal, and what a started move records. */
object UserMoveServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val kz = PartitionId("kz")
  private val uz = PartitionId("uz")
  private val regions = Vector(RegionRecord(RegionId("almaty"), kz), RegionRecord(RegionId("astana"), kz), RegionRecord(RegionId("tashkent"), uz))

  private def cell(id: String, region: String, state: CellState = CellState.Open) =
    CellRecord(
      id = CellId(id),
      regionId = RegionId(region),
      internalUrl = s"http://$id.internal:9003",
      mtlsUrl = None,
      trustedCertificates = Nil,
      state = state,
      weight = 100,
      publicKeys = Nil,
    )

  private val cells = Vector(
    cell("kz-1", "almaty"),
    cell("kz-2", "astana"),
    cell("kz-3", "astana", CellState.Draining),
    cell("kz-4", "astana", CellState.Closed),
    cell("uz-1", "tashkent"),
  )

  private val firstMoveId = UUID.fromString("00000000-0000-7000-8000-0000000000b1")
  private val secondMoveId = UUID.fromString("00000000-0000-7000-8000-0000000000b2")

  private class Env(
      held: Option[UserDirectoryRecord] = Some(UserDirectoryRecord(userId, Some(CellId("kz-1")))),
      registered: Vector[CellRecord] = cells,
  ):
    val users = stub[UserRepository]
    val moves = stub[UserMoveRepository]
    val topology = stub[TopologyRepository]
    val random = stub[SecureRandom]
    val service = UserMoveService.Impl(users, moves, topology, random)
    val ready: UIO[Unit] =
      users.find.succeedsWith(held) *> topology.getCells.succeedsWith(registered) *> topology.getRegions.succeedsWith(regions) *>
        moves.start.succeedsWith(true) *>
        random.nextUUIDv7.returnsZIOOnCall:
          case 1 => ZIO.succeed(firstMoveId)
          case _ => ZIO.succeed(secondMoveId)

  private def refused(env: Env, to: String) = env.ready *> env.service.start(userId, CellId(to)).either

  def spec = suite("UserMoveService")(
    test("a move to another cell of the partition is recorded as leaving, by an administrator, due now") {
      val env = Env()
      for
        _ <- TestClock.setTime(Instant.parse("2027-02-01T10:00:00Z"))
        _ <- env.ready
        result <- env.service.start(userId, CellId("kz-2")).either
      yield assertTrue(
        result.isRight,
        env.moves.start.calls == List(
          UserMove(
            userId = userId,
            fromCell = CellId("kz-1"),
            toCell = CellId("kz-2"),
            phase = MovePhase.Leaving,
            startedAt = Instant.parse("2027-02-01T10:00:00Z"),
            updatedAt = Instant.parse("2027-02-01T10:00:00Z"),
            attempts = 0,
            error = None,
            reason = MoveReason.Admin,
            notBefore = Instant.parse("2027-02-01T10:00:00Z"),
            departedAt = None,
            moveId = firstMoveId,
            digest = None,
          ),
        ),
      )
    },
    test("every move is given an id of its own, so that the next move of the same user is told apart from the one before") {
      val env = Env()
      for
        _ <- env.ready
        _ <- env.service.start(userId, CellId("kz-2"))
        _ <- env.service.start(userId, CellId("kz-4"))
      yield assertTrue(env.moves.start.calls.map(_.moveId) == List(firstMoveId, secondMoveId))
    },
    test("a closed cell may be moved to: only draining refuses") {
      val env = Env()
      for result <- refused(env, "kz-4")
      yield assertTrue(result.isRight)
    },
    test("an unknown user is not found") {
      val env = Env(held = None)
      for result <- refused(env, "kz-2")
      yield assertTrue(result == Left(MoveRefusal.NoSuchUser), env.moves.start.calls.isEmpty)
    },
    test("a user that is in no cell has no cell to leave") {
      val env = Env(held = Some(UserDirectoryRecord(userId, None)))
      for result <- refused(env, "kz-2")
      yield assertTrue(result == Left(MoveRefusal.NoCell), env.moves.start.calls.isEmpty)
    },
    test("a target that is not registered is refused") {
      val env = Env()
      for result <- refused(env, "kz-9")
      yield assertTrue(result == Left(MoveRefusal.TargetNotRegistered), env.moves.start.calls.isEmpty)
    },
    test("the user's own cell is refused") {
      val env = Env()
      for result <- refused(env, "kz-1")
      yield assertTrue(result == Left(MoveRefusal.SameCell), env.moves.start.calls.isEmpty)
    },
    test("a cell of another partition is refused") {
      val env = Env()
      for result <- refused(env, "uz-1")
      yield assertTrue(result == Left(MoveRefusal.OtherPartition), env.moves.start.calls.isEmpty)
    },
    test("a cell that is being drained is refused") {
      val env = Env()
      for result <- refused(env, "kz-3")
      yield assertTrue(result == Left(MoveRefusal.TargetDraining), env.moves.start.calls.isEmpty)
    },
    test("a user held by a cell that is not registered is refused: its partition is not known") {
      val env = Env(held = Some(UserDirectoryRecord(userId, Some(CellId("kz-9")))))
      for result <- refused(env, "kz-2")
      yield assertTrue(result == Left(MoveRefusal.SourceNotRegistered), env.moves.start.calls.isEmpty)
    },
    test("a cell whose region is not known is in no partition, and is refused") {
      val env = Env(registered = cells :+ cell("xx-1", "nowhere"))
      for result <- refused(env, "xx-1")
      yield assertTrue(result == Left(MoveRefusal.OtherPartition), env.moves.start.calls.isEmpty)
    },
    test("a user whose move is in progress is refused, the store having said so") {
      val env = Env()
      for
        _ <- env.ready
        _ <- env.moves.start.succeedsWith(false)
        result <- env.service.start(userId, CellId("kz-2")).either
      yield assertTrue(result == Left(MoveRefusal.InProgress))
    },
    test("the moves are listed as asked") {
      val env = Env()
      for
        _ <- env.moves.list.succeedsWith(Vector.empty)
        _ <- env.service.list(Some(CellId("kz-1")), Some(MovePhase.Flipped), 20)
      yield assertTrue(env.moves.list.calls == List((Some(CellId("kz-1")), Some(MovePhase.Flipped), 20)))
    },
  )
