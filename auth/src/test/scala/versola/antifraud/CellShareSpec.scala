package versola.antifraud

import versola.auth.TestEnvConfig
import versola.oauth.handoff.{CellInfo, CellRegistry}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

/** versolauth/versola#1052: the part of a limit by address that a cell applies. */
object CellShareSpec extends UnitSpecBase:

  private def cell(id: String, weight: Int, state: String = "open") = CellInfo(id, state, weight, "kz", Nil)

  private val own = TestEnvConfig.cellId: String

  def spec = suite("CellShare")(
    suite("of")(
      test("a cell that takes users has its weight over the weights of the cells that take them") {
        assertTrue(
          CellShare.of(Some("kz-1"), List(cell("kz-1", 1), cell("kz-2", 3))) == CellShare.Share(1, 4),
          CellShare.of(Some("kz-2"), List(cell("kz-1", 1), cell("kz-2", 3))) == CellShare.Share(3, 4),
        )
      },
      test("a cell that is alone is the whole deployment") {
        assertTrue(CellShare.of(Some("kz-1"), List(cell("kz-1", 7))).of(100) == 100)
      },
      test("cells that do not take users are left out of the sum") {
        val cells = List(cell("kz-1", 2), cell("kz-2", 8, state = "closed"), cell("kz-3", 6, state = "draining"), cell("kz-4", 0), cell("kz-5", 2))
        assertTrue(CellShare.of(Some("kz-1"), cells) == CellShare.Share(2, 4))
      },
      test("a cell that takes no users has one share of the registered cells, whatever it is not taking") {
        val cells = List(cell("kz-1", 5, state = "closed"), cell("kz-2", 1), cell("kz-3", 9))
        assertTrue(
          CellShare.of(Some("kz-1"), cells) == CellShare.Share(1, 3),
          CellShare.of(Some("kz-1"), List(cell("kz-1", 5, state = "draining"), cell("kz-2", 1))) == CellShare.Share(1, 2),
          // Open, but with no weight: no user is placed there either.
          CellShare.of(Some("kz-1"), List(cell("kz-1", 0), cell("kz-2", 1), cell("kz-3", 1), cell("kz-4", 1))) == CellShare.Share(1, 4),
        )
      },
      test("an auth that is no cell applies the limit whole") {
        assertTrue(CellShare.of(None, List(cell("kz-1", 1), cell("kz-2", 3))) == CellShare.Whole)
      },
      test("a cell that has registered no cell applies the limit whole") {
        assertTrue(CellShare.of(Some("kz-1"), Nil) == CellShare.Whole)
      },
      test("weights that do not divide evenly: the limit is rounded up exactly, and only up") {
        val third = CellShare.of(Some("kz-1"), List(cell("kz-1", 1), cell("kz-2", 1), cell("kz-3", 1)))
        // 0.34 as a double is a hair above 34/100 and would round up to 35.
        assertTrue(
          third == CellShare.Share(1, 3),
          third.of(100) == 34,
          third.of(99) == 33,
          third.of(10) == 4,
          CellShare.Share(34, 100).of(100) == 34,
          CellShare.Share(2, 7).of(7) == 2,
        )
      },
    ),
    suite("Share.of")(
      test("a limit of 0 stays no limit") {
        assertTrue(CellShare.Share(1, 3).of(0) == 0, CellShare.Whole.of(0) == 0)
      },
      test("a share that would round a limit to 0 gives 1") {
        assertTrue(CellShare.Share(1, 1000).of(1) == 1, CellShare.Share(1, 1000).of(5) == 1, CellShare.Share(1, 1000).of(1001) == 2)
      },
      test("a limit does not overflow") {
        assertTrue(CellShare.Share(Int.MaxValue.toLong, Int.MaxValue.toLong).of(Int.MaxValue) == Int.MaxValue)
      },
      test("the share is reported as a fraction") {
        assertTrue(CellShare.Share(1, 4).value == 0.25, CellShare.Whole.value == 1.0)
      },
    ),
    suite("live")(
      test("reads the registry on every call, so a change of weights shows without a restart") {
        val registry = stub[CellRegistry]
        for
          _ <- registry.all.succeedsWith(List(cell(own, 1), cell("kz-2", 1)))
          share = CellShare.live.build.map(_.get)
          first <- ZIO.scoped(share.flatMap(_.current)).provide(ZLayer.succeed(registry: CellRegistry), ZLayer.succeed(TestEnvConfig.cellCoreConfig))
          _ <- registry.all.succeedsWith(List(cell(own, 1), cell("kz-2", 3)))
          second <- ZIO.scoped(share.flatMap(_.current)).provide(ZLayer.succeed(registry: CellRegistry), ZLayer.succeed(TestEnvConfig.cellCoreConfig))
        yield assertTrue(first == CellShare.Share(1, 2), second == CellShare.Share(1, 4))
      },
      test("an empty registry, and an auth that is no cell, give the whole limit") {
        val registry = stub[CellRegistry]
        for
          _ <- registry.all.succeedsWith(Nil)
          empty <- ZIO.scoped(CellShare.live.build.flatMap(_.get.current))
            .provide(ZLayer.succeed(registry: CellRegistry), ZLayer.succeed(TestEnvConfig.cellCoreConfig))
          _ <- registry.all.succeedsWith(List(cell(own, 1), cell("kz-2", 3)))
          plain <- ZIO.scoped(CellShare.live.build.flatMap(_.get.current))
            .provide(ZLayer.succeed(registry: CellRegistry), ZLayer.succeed(TestEnvConfig.coreConfig))
        yield assertTrue(empty == CellShare.Whole, plain == CellShare.Whole)
      },
    ),
  )
