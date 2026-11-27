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

import java.util.UUID

/** Emptying a draining cell (versolauth/versola#1051): who is taken, to which cell, how many at a time, and when a failed one is tried
  * again. */
object CellDrainSpec extends ZIOSpecDefault, ZIOStubs:

  private val kz = PartitionRecord(PartitionId("kz"))
  private val uz = PartitionRecord(PartitionId("uz"))
  private val almaty = RegionRecord(RegionId("almaty"), kz.id)
  private val astana = RegionRecord(RegionId("astana"), kz.id)
  private val tashkent = RegionRecord(RegionId("tashkent"), uz.id)
  private val regions = Vector(almaty, astana, tashkent)
  private val settings = UserMoveSettings()

  private def cell(id: String, region: RegionRecord, state: CellState = CellState.Open, weight: Int = 100) =
    CellRecord(
      id = CellId(id),
      regionId = region.id,
      internalUrl = s"http://$id.internal:9003",
      mtlsUrl = None,
      trustedCertificates = Nil,
      state = state,
      weight = weight,
      publicKeys = Nil,
    )

  private def user(n: Int) = UserId(UUID.fromString(f"00000000-0000-0000-0000-$n%012d"))

  private class Fixture(cells: Vector[CellRecord]):
    val topology = stub[TopologyRepository]
    val moves = stub[UserMoveRepository]
    val drain: UIO[CellDrain.Live] =
      for
        _ <- topology.getCells.succeedsWith(cells)
        _ <- topology.getRegions.succeedsWith(regions)
        random <- ZIO.service[SecureRandom].provide(SecureRandom.live)
        fiber <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
      yield CellDrain.Live(settings, topology, moves, random, fiber)

    /** The cell hands out `n` users, one per call, and then has no more. */
    def hasUsers(n: Int) =
      moves.startDrain.returnsZIOOnCall:
        case call if call <= n => ZIO.some(user(call))
        case _ => ZIO.none

  def spec = suite("CellDrain")(
    test("the users of a draining cell are moved to an open cell of the same partition, and to none of another partition") {
      val draining = cell("kz-1", almaty, CellState.Draining)
      val f = Fixture(Vector(draining, cell("kz-2", astana), cell("uz-1", tashkent)))
      for
        drain <- f.drain
        _ <- f.hasUsers(3)
        started <- drain.processOnce
      yield assertTrue(
        started == 3,
        f.moves.startDrain.calls.map(_._1).forall(_ == draining.id),
        f.moves.startDrain.calls.map(_._2).forall(_ == CellId("kz-2")),
      )
    },
    test("the target is picked by the weights of the open cells, from the project's random") {
      val f = Fixture(Vector(cell("kz-1", almaty, CellState.Draining), cell("kz-2", astana, weight = 100), cell("kz-3", astana, weight = 300)))
      for
        drain <- f.drain
        _ <- TestRandom.feedDoubles(0.1, 0.5, 0.9)
        _ <- f.hasUsers(3)
        _ <- drain.processOnce
      yield assertTrue(f.moves.startDrain.calls.map(_._2).take(3) == List(CellId("kz-2"), CellId("kz-3"), CellId("kz-3")))
    },
    test("at most Parallel moves are started in a pass, and the cap is handed to the store which counts the unfinished ones") {
      val f = Fixture(Vector(cell("kz-1", almaty, CellState.Draining), cell("kz-2", astana)))
      for
        drain <- f.drain
        _ <- f.hasUsers(100)
        started <- drain.processOnce
      yield assertTrue(
        started == settings.parallel,
        f.moves.startDrain.calls.size == settings.parallel,
        f.moves.startDrain.calls.map(_._4).forall(_ == settings.parallel),
      )
    },
    test("with nobody left to take, or the cap already reached, the pass stops") {
      val f = Fixture(Vector(cell("kz-1", almaty, CellState.Draining), cell("kz-2", astana)))
      for
        drain <- f.drain
        _ <- f.hasUsers(1)
        started <- drain.processOnce
        // the store answers `None` for the cap as it does for nobody
        again <- drain.processOnce
      yield assertTrue(started == 1, again == 0, f.moves.startDrain.calls.size == 3)
    },
    test("with no open cell in the partition nothing is started and the cell is reported as blocked") {
      val draining = cell("kz-1", almaty, CellState.Draining)
      val others = Vector(
        cell("kz-2", astana, CellState.Closed),
        cell("kz-3", astana, CellState.Draining),
        cell("kz-4", astana, weight = 0),
        cell("uz-1", tashkent),
      )
      val cells = draining +: others
      val f = Fixture(cells)
      for
        drain <- f.drain
        started <- drain.processOnce
      yield assertTrue(
        started == 0,
        f.moves.startDrain.calls.isEmpty,
        CellDrain.blocked(draining, cells, regions).contains(CellDrain.NoOpenCell),
        // a cell that is not being drained is never said to be blocked
        CellDrain.blocked(others(0), cells, regions).isEmpty,
        CellDrain.blocked(draining, cells :+ cell("kz-5", astana), regions).isEmpty,
      )
    },
    test("a cell that is open or closed is not drained") {
      val f = Fixture(Vector(cell("kz-1", almaty), cell("kz-2", astana, CellState.Closed), cell("kz-3", astana)))
      for
        drain <- f.drain
        started <- drain.processOnce
      yield assertTrue(started == 0, f.moves.startDrain.calls.isEmpty)
    },
    test("a move that failed is offered again only RetryAfter after it did: the store is given that time") {
      val f = Fixture(Vector(cell("kz-1", almaty, CellState.Draining), cell("kz-2", astana)))
      for
        drain <- f.drain
        _ <- TestClock.setTime(java.time.Instant.parse("2027-03-01T12:00:00Z"))
        _ <- f.hasUsers(1)
        _ <- drain.processOnce
        (_, _, _, _, now, retryBefore) = f.moves.startDrain.calls.head
      yield assertTrue(now == java.time.Instant.parse("2027-03-01T12:00:00Z"), retryBefore == now.minusSeconds(600))
    },
    test("every draining cell is emptied, each into the open cells of its own partition") {
      val f = Fixture(
        Vector(cell("kz-1", almaty, CellState.Draining), cell("kz-2", astana), cell("uz-1", tashkent, CellState.Draining), cell("uz-2", tashkent)),
      )
      for
        drain <- f.drain
        _ <- f.hasUsers(8)
        _ <- drain.processOnce
        calls = f.moves.startDrain.calls.map(call => (call._1, call._2)).distinct
      yield assertTrue(calls.toSet == Set((CellId("kz-1"), CellId("kz-2")), (CellId("uz-1"), CellId("uz-2"))))
    },
    test("a failure of the store is logged and does not stop the next pass") {
      val f = Fixture(Vector(cell("kz-1", almaty, CellState.Draining), cell("kz-2", astana)))
      for
        drain <- f.drain
        _ <- f.moves.startDrain.returnsZIOOnCall:
          case 1 => ZIO.fail(new RuntimeException("database down"))
          case _ => ZIO.none
        first <- drain.processOnce
        second <- drain.processOnce
      yield assertTrue(first == 0, second == 0, f.moves.startDrain.calls.size == 2)
    },
  ) @@ TestAspect.silentLogging
