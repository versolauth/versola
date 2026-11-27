package versola.central.users

import com.nimbusds.jwt.SignedJWT
import versola.central.antifraud.*
import versola.central.configuration.topology.{CellId, CellRecord, CellState, RegionId, TopologyRepository}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.UnitSpecBase
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
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
      publicKeys = Nil,
    )

  private val kz1 = "http://kz-1.internal:9003"
  private val kz2 = "http://kz-2.internal:9003"

  private val entryCheck = ListEntryCheck(
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = Some("4915"),
    phone = None,
    tenantId = None,
    expiresAt = None,
    reason = None,
    source = ListEntrySource.Manual,
    shadow = false,
    preview = false,
  )

  private def statusOf(version: Long) = AntifraudInstanceStatus(
    decision = AntifraudDecisionStatus(enabled = true),
    lists = AntifraudListsStatus(enabled = true, snapshotAgeSeconds = Some(1), size = 4, skipped = 0, lastRefreshError = None),
    tuning = AntifraudTuningStatus(available = true, version = Some(version), ageSeconds = Some(2), lastRefreshError = None),
    cellShare = Some(1.0),
  )

  private val reset = ResetCountersRequest(CounterScope.velocity, Some(CounterValueType.phone), Some("+77011234567"), userId)

  private val cells = Vector(cell("kz-1", "http://kz-1.internal:9003"), cell("kz-2", "http://kz-2.internal:9003"))

  class Env:
    val configured = stub[AuthClient]
    val ofCells = Map("http://kz-1.internal:9003" -> stub[AuthClient], "http://kz-2.internal:9003" -> stub[AuthClient])
    val asked: Ref[List[String]] = Unsafe.unsafe(u ?=> Ref.unsafe.make(List.empty[String])(using u))
    val audiences: Ref[List[CellId]] = Unsafe.unsafe(u ?=> Ref.unsafe.make(List.empty[CellId])(using u))
    val users = stub[UserRepository]
    val topology = stub[TopologyRepository]
    val client = CellAuthClient(
      configured = configured,
      configuredCell = Some(CellId("local-1")),
      at = (url: URL, cell: CellId) =>
        Unsafe.unsafe(u ?=> Runtime.default.unsafe.run(asked.update(_ :+ url.encode) *> audiences.update(_ :+ cell))(using summon, u).getOrThrow())
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
        audiences <- env.audiences.get
      yield assertTrue(
        // the client of a cell is made for that cell, which is who its token is for
        audiences == List(CellId("kz-2")),
        env.ofCells("http://kz-2.internal:9003").getUserClaims.calls == List(userId),
        env.ofCells("http://kz-2.internal:9003").deleteUser.calls == List(userId),
        env.configured.getUserClaims.calls.isEmpty,
        env.ofCells("http://kz-1.internal:9003").getUserClaims.calls.isEmpty,
        // One client for an address, however many calls go to it.
        asked == List("http://kz-2.internal:9003"),
      )
    },
    test("a deletion addressed to a registered cell goes to that cell, whatever the directory says of the user") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz2).deleteUser.succeedsWith(())
        // The directory row is gone, as it is when the queued deletion is delivered.
        _ <- env.users.find.succeedsWith(None)
        _ <- env.client.deleteUserOf(Some(CellId("kz-2")), userId)
      yield assertTrue(
        env.ofCells(kz2).deleteUser.calls == List(userId),
        env.ofCells(kz1).deleteUser.calls.isEmpty,
        env.configured.deleteUser.calls.isEmpty,
        env.users.find.calls.isEmpty,
      )
    },
    test("a deletion addressed to the cell the configured auth is, while it is not registered, or to no cell, goes to the configured auth") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.configured.deleteUser.succeedsWith(())
        _ <- env.client.deleteUserOf(Some(CellId("local-1")), userId)
        _ <- env.client.deleteUserOf(None, userId)
        asked <- env.asked.get
      yield assertTrue(env.configured.deleteUser.calls == List(userId, userId), env.ofCells.values.forall(_.deleteUser.calls.isEmpty), asked.isEmpty)
    },
    test("a user of no cell, of the unregistered cell the configured auth is, and one the directory does not have go to the configured auth") {
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
    test("a user of a cell that is gone is asked of no auth: a read or a change fails, a deletion has nothing to delete") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.held(Some("kz-9"))
        read <- env.client.getUserClaims(userId).either
        change <- env.client.setPassword(userId, "irrelevant").either
        _ <- env.client.deleteUser(userId)
        _ <- env.client.deleteUserOf(Some(CellId("kz-9")), userId)
        asked <- env.asked.get
      yield assertTrue(
        read == Left(AuthClient.CellNotRegistered(CellId("kz-9"))),
        change == Left(AuthClient.CellNotRegistered(CellId("kz-9"))),
        env.configured.getUserClaims.calls.isEmpty,
        env.configured.setPassword.calls.isEmpty,
        env.configured.deleteUser.calls.isEmpty,
        asked.isEmpty,
      )
    },
    test("a superseded session is ended in the cell that holds the user, and nowhere else") {
      val env = Env()
      for
        _ <- env.held(Some("kz-2"))
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz2).supersedeSession.succeedsWith(true)
        ended <- env.client.supersedeSession(userId, "c2lk")
      yield assertTrue(
        ended,
        env.ofCells(kz2).supersedeSession.calls == List((userId, "c2lk")),
        env.ofCells(kz1).supersedeSession.calls.isEmpty,
        env.configured.supersedeSession.calls.isEmpty,
      )
    },
    test("a superseded session of a user of no cell, or of the unregistered cell the configured auth is, goes to the configured auth") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.configured.supersedeSession.succeedsWith(false)
        _ <- env.held(None)
        first <- env.client.supersedeSession(userId, "c2lk")
        _ <- env.held(Some("local-1"))
        second <- env.client.supersedeSession(userId, "c2lk")
      yield assertTrue(
        !first,
        !second,
        env.configured.supersedeSession.calls.size == 2,
        env.ofCells.values.forall(_.supersedeSession.calls.isEmpty),
      )
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
    test("the rule catalogue and the status of the configured auth stay with it") {
      val env = Env()
      for
        _ <- env.configured.antifraudStatus.failsWith(new RuntimeException("asked"))
        _ <- env.configured.antifraudRules.failsWith(new RuntimeException("asked"))
        status <- env.client.antifraudStatus.either
        rules <- env.client.antifraudRules.either
      yield assertTrue(
        status.isLeft,
        rules.isLeft,
        env.ofCells.values.forall(cell => cell.antifraudStatus.calls.isEmpty && cell.antifraudRules.calls.isEmpty),
      )
    },
    test("the check of an entry or of the settings goes to the configured auth and to no cell") {
      val env = Env()
      val settings = SettingsCheck(settings = None, patch = None)
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.configured.checkAntifraudListEntry.succeedsWith(CheckedListEntry("hashed", None))
        _ <- env.configured.checkAntifraudSettings.failsWith(AntifraudListError.Invalid("blockAt is out of range"))
        checked <- env.client.checkAntifraudListEntry(entryCheck)
        refused <- env.client.checkAntifraudSettings(settings).either
      yield assertTrue(
        checked == CheckedListEntry("hashed", None),
        refused == Left(AntifraudListError.Invalid("blockAt is out of range")),
        env.configured.checkAntifraudListEntry.calls == List(entryCheck),
        env.configured.checkAntifraudSettings.calls == List(settings),
        env.ofCells.values.forall(cell => cell.checkAntifraudListEntry.calls.isEmpty && cell.checkAntifraudSettings.calls.isEmpty),
      )
    },
    test("a preview is asked of every cell and answers the sum of what they matched and saw, with the value of the first") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <-
          env.ofCells(kz1).previewAntifraudListEntry.succeedsWith(CheckedListEntry("hashed", Some(PreviewResponse(matched = Some(3), total = 100))))
        _ <- env.ofCells(kz2).previewAntifraudListEntry.succeedsWith(CheckedListEntry("hashed", Some(PreviewResponse(matched = Some(4), total = 50))))
        previewed <- env.client.previewAntifraudListEntry(entryCheck)
      yield assertTrue(
        previewed == CheckedListEntry("hashed", Some(PreviewResponse(matched = Some(7), total = 150))),
        env.ofCells.values.forall(_.previewAntifraudListEntry.calls == List(entryCheck)),
        env.configured.previewAntifraudListEntry.calls.isEmpty,
      )
    },
    test("a preview of a value no cell has a column for is not counted, and one cell that counts it is enough") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).previewAntifraudListEntry.succeedsWith(CheckedListEntry("10.0.0.1", Some(PreviewResponse(matched = None, total = 100))))
        _ <- env.ofCells(kz2).previewAntifraudListEntry.succeedsWith(CheckedListEntry("10.0.0.1", Some(PreviewResponse(matched = None, total = 50))))
        uncounted <- env.client.previewAntifraudListEntry(entryCheck)
      yield assertTrue(uncounted.preview.contains(PreviewResponse(matched = None, total = 150)))
    },
    test("a preview fails when one cell cannot answer: a sum without it would understate what the entry does") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <-
          env.ofCells(kz1).previewAntifraudListEntry.succeedsWith(CheckedListEntry("hashed", Some(PreviewResponse(matched = Some(3), total = 100))))
        _ <- env.ofCells(kz2).previewAntifraudListEntry.failsWith(new RuntimeException("kz-2 is down"))
        failed <- env.client.previewAntifraudListEntry(entryCheck).either
      yield assertTrue(failed.left.exists(_.isInstanceOf[RuntimeException]))
    },
    test("a cell's refusal of an entry fails the preview with that refusal") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).previewAntifraudListEntry.failsWith(AntifraudListError.Invalid("not an IP address"))
        _ <- env.ofCells(kz2).previewAntifraudListEntry.failsWith(AntifraudListError.Invalid("not an IP address"))
        failed <- env.client.previewAntifraudListEntry(entryCheck).either
      yield assertTrue(failed == Left(AntifraudListError.Invalid("not an IP address")))
    },
    test("a preview goes to the configured auth when there is no cell") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        _ <- env.configured.previewAntifraudListEntry.succeedsWith(CheckedListEntry("v", Some(PreviewResponse(matched = Some(1), total = 2))))
        previewed <- env.client.previewAntifraudListEntry(entryCheck)
      yield assertTrue(
        previewed == CheckedListEntry("v", Some(PreviewResponse(matched = Some(1), total = 2))),
        env.configured.previewAntifraudListEntry.calls == List(entryCheck),
      )
    },
    test("the hits of an entry are summed over the cells, with the latest time") {
      val env = Env()
      val shared = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
      val onlyInKz2 = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
      val early = Instant.parse("2026-11-01T00:00:00Z")
      val late = Instant.parse("2026-11-02T00:00:00Z")
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).antifraudListHits.succeedsWith(List(ListHits(id = shared, count = 3, lastAt = late)))
        _ <- env.ofCells(kz2).antifraudListHits.succeedsWith(
          List(ListHits(id = shared, count = 4, lastAt = early), ListHits(id = onlyInKz2, count = 1, lastAt = early)),
        )
        hits <- env.client.antifraudListHits(List(shared, onlyInKz2))
      yield assertTrue(
        hits.toSet == Set(ListHits(id = shared, count = 7, lastAt = late), ListHits(id = onlyInKz2, count = 1, lastAt = early)),
        env.ofCells.values.forall(_.antifraudListHits.calls == List(List(shared, onlyInKz2))),
      )
    },
    test("the hits of a cell that does not answer are left out, and the others are answered") {
      val env = Env()
      val shared = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
      val at = Instant.parse("2026-11-01T00:00:00Z")
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).antifraudListHits.succeedsWith(List(ListHits(id = shared, count = 3, lastAt = at)))
        _ <- env.ofCells(kz2).antifraudListHits.failsWith(new RuntimeException("kz-2 is down"))
        hits <- env.client.antifraudListHits(List(shared))
      yield assertTrue(hits == List(ListHits(id = shared, count = 3, lastAt = at)))
    } @@ TestAspect.silentLogging,
    test("the reset of the counters goes to every cell and answers the highest generation") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).resetAntifraudCounters.succeedsWith(ResetCountersResponse(5))
        _ <- env.ofCells(kz2).resetAntifraudCounters.succeedsWith(ResetCountersResponse(9))
        answer <- env.client.resetAntifraudCounters(reset)
      yield assertTrue(
        answer == ResetCountersResponse(9),
        env.ofCells.values.forall(_.resetAntifraudCounters.calls == List(reset)),
        env.configured.resetAntifraudCounters.calls.isEmpty,
      )
    },
    test("the reset fails when one cell cannot be reached: the operator is not told it is done while a cell still counts") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).resetAntifraudCounters.succeedsWith(ResetCountersResponse(5))
        _ <- env.ofCells(kz2).resetAntifraudCounters.failsWith(new RuntimeException("kz-2 is down"))
        failed <- env.client.resetAntifraudCounters(reset).either
      yield assertTrue(failed.left.exists(_.isInstanceOf[RuntimeException]))
    },
    test("the reset goes to the configured auth when there is no cell") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        _ <- env.configured.resetAntifraudCounters.succeedsWith(ResetCountersResponse(2))
        answer <- env.client.resetAntifraudCounters(reset)
      yield assertTrue(answer == ResetCountersResponse(2), env.configured.resetAntifraudCounters.calls == List(reset))
    },
    test("the status of the cells lists every registered cell with what its auth says, in the order of the topology") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).antifraudStatus.succeedsWith(statusOf(version = 7))
        _ <- env.ofCells(kz2).antifraudStatus.succeedsWith(statusOf(version = 6))
        listed <- env.client.antifraudStatusOfCells
      yield assertTrue(
        listed.toSet == Set(
          AntifraudCellStatus(cell = "kz-1", status = Some(statusOf(version = 7)), error = None),
          AntifraudCellStatus(cell = "kz-2", status = Some(statusOf(version = 6)), error = None),
        ),
        env.configured.antifraudStatus.calls.isEmpty,
      )
    },
    test("a cell that does not answer is listed with the reason and no status, and the others are still answered") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).antifraudStatus.succeedsWith(statusOf(version = 7))
        _ <- env.ofCells(kz2).antifraudStatus.failsWith(new RuntimeException("connection refused"))
        listed <- env.client.antifraudStatusOfCells
      yield assertTrue(
        listed.toSet == Set(
          AntifraudCellStatus(cell = "kz-1", status = Some(statusOf(version = 7)), error = None),
          AntifraudCellStatus(cell = "kz-2", status = None, error = Some("connection refused")),
        ),
      )
    },
    test("a cell that refuses the question is listed with the refusal, and a long reason is cut") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- env.ofCells(kz1).antifraudStatus.failsWith(AntifraudListError.Invalid("not today"))
        _ <- env.ofCells(kz2).antifraudStatus.failsWith(new RuntimeException("x" * 500))
        listed <- env.client.antifraudStatusOfCells
        byCell = listed.map(c => c.cell -> c).toMap
      yield assertTrue(
        byCell("kz-1").status.isEmpty,
        byCell("kz-1").error.exists(_.contains("not today")),
        byCell("kz-2").error.map(_.length).contains(200),
      )
    },
    test("with no cell registered there is none to list") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        listed <- env.client.antifraudStatusOfCells
      yield assertTrue(listed == Nil)
    },
    test("every auth is sent a token made for it alone: the audience of a cell is its id, of the configured auth its cell id, or `auth`") {
      val users = stub[UserRepository]
      val topology = stub[TopologyRepository]
      // answers every call, and keeps the host it was sent to with the bearer token it carried
      def driver(seen: Ref[List[(String, String)]]) = new ZClient.Driver[Any, Scope, Throwable]:
        override def request(
            version: Version,
            method: Method,
            url: URL,
            headers: Headers,
            body: Body,
            sslConfig: Option[ClientSSLConfig],
            proxy: Option[Proxy],
        )(using trace: Trace): ZIO[Scope, Throwable, Response] =
          val bearer = headers.get(Header.Authorization).collect { case Header.Authorization.Bearer(token) => token.stringValue }
          seen.update(_ :+ (url.host.getOrElse("") -> bearer.getOrElse(""))).as(Response.status(Status.NoContent))

        override def socket[Env1 <: Any](
            version: Version,
            url: URL,
            headers: Headers,
            app: WebSocketApp[Env1],
        )(using trace: Trace, ev: Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
          ZIO.dieMessage("WebSocket not used in AuthClient tests")

      def audienceOf(token: String): (String, List[String]) =
        val parsed = SignedJWT.parse(token)
        (parsed.getHeader.getKeyID, parsed.getJWTClaimsSet.getAudience.toArray.toList.map(_.toString))

      def run(config: CentralConfig) =
        for
          seen <- Ref.make(List.empty[(String, String)])
          tokens <- ZIO.service[AuthTokenService].provide(ZLayer.succeed(config) >>> AuthTokenService.live)
          http = ZClient.fromDriver(driver(seen))
          _ <- topology.getCells.succeedsWith(cells)
          client = CellAuthClient(
            configured = AuthClient.Impl(http, config.auth.url, tokens, AuthTokenService.audienceOfConfigured(config)),
            configuredCell = config.auth.cellId,
            at = (url, cell) => AuthClient.Impl(http, url, tokens, cell),
            users = users,
            topology = topology,
          )
          // one to each cell by its id, one to the configured auth, then the nudge to every cell
          _ <- client.deleteUserOf(Some(CellId("kz-1")), userId)
          _ <- client.deleteUserOf(Some(CellId("kz-2")), userId)
          _ <- client.deleteUserOf(None, userId)
          _ <- client.syncConfiguration()
          calls <- seen.get
        yield calls.map((host, token) => host -> audienceOf(token))

      val named = TestCentralConfig.config.copy(auth = TestCentralConfig.authConfig.copy(cellId = Some(CellId("eu-1"))))
      for
        plain <- run(TestCentralConfig.config)
        namedCalls <- run(named)
        service = TestCentralConfig.serviceKeyPair.keyId
      yield assertTrue(
        // to kz-1 only a token for kz-1, to kz-2 only one for kz-2, whichever call it is, and always under the service key
        plain.filter(_._1 == "kz-1.internal").forall(_._2 == (service, List("kz-1"))),
        plain.filter(_._1 == "kz-2.internal").forall(_._2 == (service, List("kz-2"))),
        plain.count(_._1 == "kz-1.internal") == 2,
        plain.count(_._1 == "kz-2.internal") == 2,
        // the configured auth has no cell id: its audience is `auth`
        plain.filter(_._1 == "localhost").map(_._2) == List((service, List("auth"))),
        // with one, it is that
        namedCalls.filter(_._1 == "localhost").map(_._2) == List((service, List("eu-1"))),
      )
    },
    test("a call of a move goes to the cell it is addressed to, whatever cell the directory names for the user, and to no other") {
      val env = Env()
      val moveId = UUID.fromString("00000000-0000-7000-8000-0000000000a1")
      val sealedRows = AuthClient.DepartureExport("e", "d")
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        _ <- ZIO.foreach(env.ofCells.values.toList)(c =>
          c.depart.succeedsWith(()) *> c.arrive.succeedsWith(()) *> c.exportDeparture.succeedsWith(sealedRows) *> c.keepArrival.succeedsWith(()) *>
            c.completeDeparture.succeedsWith(()) *> c.discardArrival.succeedsWith(()),
        )
        _ <- env.client.depart(CellId("kz-1"), userId, CellId("kz-2"), moveId)
        exported <- env.client.exportDeparture(CellId("kz-1"), userId)
        _ <- env.client.arrive(CellId("kz-2"), userId, CellId("kz-1"), moveId, exported.envelope)
        _ <- env.client.keepArrival(CellId("kz-2"), userId, moveId)
        _ <- env.client.completeDeparture(CellId("kz-1"), userId, moveId, exported.digest)
        _ <- env.client.discardArrival(CellId("kz-2"), userId, moveId)
      yield assertTrue(
        env.ofCells(kz1).depart.calls == List((CellId("kz-1"), userId, CellId("kz-2"), moveId)),
        env.ofCells(kz1).exportDeparture.calls == List((CellId("kz-1"), userId)),
        env.ofCells(kz2).arrive.calls == List((CellId("kz-2"), userId, CellId("kz-1"), moveId, "e")),
        env.ofCells(kz2).keepArrival.calls == List((CellId("kz-2"), userId, moveId)),
        env.ofCells(kz1).completeDeparture.calls == List((CellId("kz-1"), userId, moveId, "d")),
        env.ofCells(kz2).discardArrival.calls == List((CellId("kz-2"), userId, moveId)),
        env.ofCells(kz2).depart.calls.isEmpty,
        env.ofCells(kz1).arrive.calls.isEmpty,
        env.ofCells(kz1).keepArrival.calls.isEmpty,
        env.ofCells(kz2).completeDeparture.calls.isEmpty,
        // the directory is not asked: the move talks to both cells whatever it says
        env.users.find.calls.isEmpty,
        env.configured.depart.calls.isEmpty,
        env.configured.arrive.calls.isEmpty,
        env.configured.keepArrival.calls.isEmpty,
      )
    },
    test("a call of a move to a cell that is not registered fails and does not fall back to the configured auth") {
      val env = Env()
      for
        _ <- env.topology.getCells.succeedsWith(cells)
        departed <- env.client.depart(CellId("kz-9"), userId, CellId("kz-2"), UUID.randomUUID()).either
        summarised <- env.client.departuresSummary(CellId("kz-9")).either
      yield assertTrue(
        departed == Left(AuthClient.CellNotRegistered(CellId("kz-9"))),
        summarised == Left(AuthClient.CellNotRegistered(CellId("kz-9"))),
        env.configured.depart.calls.isEmpty,
        env.configured.departuresSummary.calls.isEmpty,
      )
    },
  )
