package versola.central.users

import versola.central.configuration.topology.{CellId, CellRecord, CellState, RegionId, TopologyRepository}
import versola.util.UnitSpecBase
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** Which `auth` central calls about a user once there are several (versolauth/versola#547, Phase 2 step 3). */
object CellAuthClientSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private def cell(id: String, url: String) =
    CellRecord(
      id = CellId(id),
      regionId = RegionId("almaty"),
      internalUrl = url,
      mtlsUrl = None,
      trustedCertificates = Nil,
      state = CellState.Open,
      weight = 100,
    )

  private val cells = Vector(cell("kz-1", "http://kz-1.internal:9003"), cell("kz-2", "http://kz-2.internal:9003"))

  class Env:
    val configured = stub[AuthClient]
    val ofCells = Map("http://kz-1.internal:9003" -> stub[AuthClient], "http://kz-2.internal:9003" -> stub[AuthClient])
    val asked: Ref[List[String]] = Unsafe.unsafe(u ?=> Ref.unsafe.make(List.empty[String])(using u))
    val users = stub[UserRepository]
    val topology = stub[TopologyRepository]
    val client = CellAuthClient(
      configured = configured,
      at = (url: URL) =>
        Unsafe.unsafe(u ?=> Runtime.default.unsafe.run(asked.update(_ :+ url.encode))(using summon, u).getOrThrow())
        ofCells(url.encode)
      ,
      users = users,
      topology = topology,
    )

    def held(by: Option[String]) = users.find.succeedsWith(Some(UserDirectoryRecord(userId, by.map(CellId(_)))))

  def spec = suite("CellAuthClient")(
    test("a call about a user goes to the cell that holds the user, and to no other auth") {
      val env = Env()
      for
        _ <- env.held(Some("kz-2"))
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells("http://kz-2.internal:9003").getUserClaims.succeedsWith(Some(Json.Obj()))
        _ <- env.ofCells("http://kz-2.internal:9003").deleteUser.succeedsWith(())
        _ <- env.client.getUserClaims(userId)
        _ <- env.client.deleteUser(userId)
        asked <- env.asked.get
      yield assertTrue(
        env.ofCells("http://kz-2.internal:9003").getUserClaims.calls == List(userId),
        env.ofCells("http://kz-2.internal:9003").deleteUser.calls == List(userId),
        env.configured.getUserClaims.calls.isEmpty,
        env.ofCells("http://kz-1.internal:9003").getUserClaims.calls.isEmpty,
        // One client for an address, however many calls go to it.
        asked == List("http://kz-2.internal:9003"),
      )
    },
    test("a user of no cell, of a cell that is not registered, and one the directory does not have go to the configured auth") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.configured.getUserClaims.succeedsWith(None)
        _ <- env.held(None)
        _ <- env.client.getUserClaims(userId)
        _ <- env.held(Some("local-1"))
        _ <- env.client.getUserClaims(userId)
        _ <- env.users.find.succeedsWith(None)
        _ <- env.client.getUserClaims(userId)
        asked <- env.asked.get
      yield assertTrue(env.configured.getUserClaims.calls.size == 3, asked.isEmpty)
    },
    test("the nudge to read the configuration goes to every cell, and to the configured auth when there is no cell") {
      val env = Env()
      for
        _ <- ZIO.foreachDiscard(env.ofCells.values)(_.syncConfiguration().succeedsWith(()))
        _ <- env.configured.syncConfiguration().succeedsWith(())
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.client.syncConfiguration()
        withCells = (env.ofCells.values.map(_.syncConfiguration().calls.size).toList, env.configured.syncConfiguration().calls.size)
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        _ <- env.client.syncConfiguration()
      yield assertTrue(withCells == (List(1, 1), 0), env.configured.syncConfiguration().calls.size == 1)
    },
    test("the antifraud calls stay with the configured auth") {
      val env = Env()
      for
        _ <- env.configured.antifraudStatus.failsWith(new RuntimeException("asked"))
        result <- env.client.antifraudStatus.either
      yield assertTrue(result.isLeft, env.ofCells.values.forall(_.antifraudStatus.calls.isEmpty))
    },
  )
