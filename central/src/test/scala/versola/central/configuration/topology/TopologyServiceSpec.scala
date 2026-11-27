package versola.central.configuration.topology

import com.nimbusds.jose.jwk.gen.{ECKeyGenerator, RSAKeyGenerator}
import com.nimbusds.jose.jwk.{Curve, JWK}
import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.edges.{EdgeGroup, EdgeGroupId, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.central.users.{AuthClient, UserMoveRepository, UserRepository}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.TestCertificates
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TopologyServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val kz = PartitionRecord(PartitionId("kz"))
  private val almaty = RegionRecord(RegionId("almaty"), kz.id)
  private val astana = RegionRecord(RegionId("astana"), kz.id)
  private val pin = TestCertificates.generate(subject = "CN=auth-kz-1").certificatePem

  private def json(key: JWK): Json.Obj = key.toJSONString.fromJson[Json.Obj].toOption.get

  private def renamed(key: Json.Obj, kid: String): Json.Obj =
    Json.Obj(key.fields.map {
      case ("kid", _) => "kid" -> Json.Str(kid)
      case other => other
    }*)

  private def publicRsa(kid: String): Json.Obj = TestCentralConfig.newKeyPair(kid).toPublicJwk

  private val key1 = publicRsa("kz-1-key-1")
  private val key2 = publicRsa("kz-1-key-2")
  private val otherCellKey = publicRsa("kz-2-key")
  private val edgeKey = publicRsa("edge-key")

  private val cell = CellRecord(
    id = CellId("kz-1"),
    regionId = almaty.id,
    internalUrl = "http://auth-kz-1.internal:9003",
    mtlsUrl = Some("https://auth-kz-1.internal:9008"),
    trustedCertificates = List(pin),
    state = CellState.Open,
    weight = 100,
    publicKeys = List(key1),
  )

  private def edge(id: String, kind: EdgeGroupKind, key: Option[Json.Obj] = None) = EdgeRecord(
    id = EdgeId(id),
    publicKey = key,
    oldPublicKey = None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = if kind == EdgeGroupKind.Partner then EdgeGroupId("partner-acme") else EdgeGroup.DefaultId,
    groupKind = kind,
    publicUrl = None,
  )

  class Env(config: CentralConfig = TestCentralConfig.config):
    val repository = stub[TopologyRepository]
    val edges = stub[EdgeService]
    val users = stub[UserRepository]
    val moves = stub[UserMoveRepository]
    val auth = stub[AuthClient]

    /** Counts how often the cells in memory were brought up to date: a change of a cell is to be seen at once. */
    val refreshed = Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(0))
    val registry: CellRegistry = new CellRegistry:
      override def all: UIO[Vector[CellRecord]] = ZIO.succeed(Vector.empty)
      override def refreshNow: Task[Unit] = refreshed.update(_ + 1)
    val service = TopologyService.Impl(repository, edges, users, config, registry, moves, auth)

    def stored(
        regions: Vector[RegionRecord] = Vector(almaty, astana),
        cells: Vector[CellRecord] = Vector.empty,
        edgeRegions: Map[EdgeId, RegionId] = Map.empty,
        registeredEdges: Vector[EdgeRecord] = Vector.empty,
    ): UIO[Unit] =
      repository.getPartitions.succeedsWith(Vector(kz)) *>
        repository.getRegions.succeedsWith(regions) *>
        repository.getCells.succeedsWith(cells) *>
        repository.getEdgeRegions.succeedsWith(edgeRegions) *>
        edges.getAllEdges.succeedsWith(registeredEdges)

  private def summary(tokens: Long) =
    AuthClient.DeparturesSummary(
      leaving = 0,
      moved = 2,
      tokens = tokens,
      tokensUntil = Option.when(tokens > 0)(java.time.Instant.parse("2027-01-01T00:00:00Z")),
    )

  private def invalid(result: Either[Throwable | TopologyError, Unit]) = result.left.exists(_.isInstanceOf[TopologyError.Invalid])
  private def missing(result: Either[Throwable | TopologyError, Unit]) = result.left.exists(_.isInstanceOf[TopologyError.Missing])
  private def conflict(result: Either[Throwable | TopologyError, Unit]) = result.left.exists(_.isInstanceOf[TopologyError.Conflict])

  def spec = suite("TopologyService")(
    suite("partitions and regions")(
      test("a partition that already exists is a conflict") {
        val env = new Env
        for
          _ <- env.repository.createPartition.succeedsWith(false)
          result <- env.service.createPartition(kz).either
        yield assertTrue(conflict(result))
      },
      test("a region is created in a partition that exists") {
        val env = new Env
        for
          _ <- env.stored()
          _ <- env.repository.createRegion.succeedsWith(true)
          _ <- env.service.createRegion(RegionRecord(RegionId("aktau"), kz.id))
        yield assertTrue(env.repository.createRegion.calls == List(RegionRecord(RegionId("aktau"), kz.id)))
      },
      test("a region in a partition that does not exist is refused and nothing is written") {
        val env = new Env
        for
          _ <- env.stored()
          result <- env.service.createRegion(RegionRecord(RegionId("aktau"), PartitionId("uz"))).either
        yield assertTrue(invalid(result), env.repository.createRegion.calls.isEmpty)
      },
      test("a region with a cell or an edge site in it is not deleted; an empty one is") {
        val env = new Env
        for
          _ <- env.stored(cells = Vector(cell), edgeRegions = Map(EdgeId("edge-astana") -> astana.id))
          _ <- env.repository.deleteRegion.succeedsWith(true)
          withCell <- env.service.deleteRegion(almaty.id).either
          withEdge <- env.service.deleteRegion(astana.id).either
          unknown <- env.service.deleteRegion(RegionId("aktau")).either
          _ <- env.stored()
          _ <- env.service.deleteRegion(astana.id)
        yield assertTrue(conflict(withCell), conflict(withEdge), missing(unknown), env.repository.deleteRegion.calls == List(astana.id))
      },
    ),
    suite("cells")(
      test("a well-formed cell is created in a region that exists") {
        val env = new Env
        for
          _ <- env.stored()
          _ <- env.repository.createCell.succeedsWith(true)
          _ <- env.service.createCell(cell)
        yield assertTrue(env.repository.createCell.calls == List(cell))
      },
      test("a cell is refused, and nothing is written, for each thing a record may not hold") {
        val env = new Env
        val refused = List(
          "a negative weight" -> cell.copy(weight = -1),
          "an address that is not a URL" -> cell.copy(internalUrl = "auth-kz-1"),
          "an address of another scheme" -> cell.copy(internalUrl = "ftp://auth-kz-1.internal"),
          "a mutual-TLS address without TLS" -> cell.copy(mtlsUrl = Some("http://auth-kz-1.internal:9008")),
          "a pin that is not a certificate" -> cell.copy(trustedCertificates = List("not a certificate")),
          "the same pin twice" -> cell.copy(trustedCertificates = List(pin, pin)),
          "a region that does not exist" -> cell.copy(regionId = RegionId("aktau")),
        )
        for
          _ <- env.stored()
          results <- ZIO.foreach(refused)((name, record) => env.service.createCell(record).either.map(name -> _))
        yield assertTrue(results.filterNot(result => invalid(result._2)).map(_._1).isEmpty, env.repository.createCell.calls.isEmpty)
      },
      test("a cell is not registered under an id that is reserved: `auth` and `central` are what an auth's token is made by and for") {
        val env = new Env
        for
          _ <- env.stored()
          reservedAuth <- env.service.createCell(cell.copy(id = CellId("auth"))).either
          reservedCentral <- env.service.createCell(cell.copy(id = CellId("central"))).either
        yield assertTrue(
          invalid(reservedAuth),
          invalid(reservedCentral),
          env.repository.createCell.calls.isEmpty,
          TopologyService.ReservedCellIds == Set("auth", "central"),
        )
      },
      test("a weight of zero, no mutual-TLS listener and no pins are allowed: a cell that takes no new users and is reached in the clear") {
        val env = new Env
        val plain = cell.copy(mtlsUrl = None, trustedCertificates = Nil, weight = 0)
        for
          _ <- env.stored()
          _ <- env.repository.createCell.succeedsWith(true)
          _ <- env.service.createCell(plain)
        yield assertTrue(env.repository.createCell.calls == List(plain))
      },
      test("a cell that already exists is a conflict") {
        val env = new Env
        for
          _ <- env.stored()
          _ <- env.repository.createCell.succeedsWith(false)
          result <- env.service.createCell(cell).either
        yield assertTrue(conflict(result))
      },
      test("an update changes a cell's addresses, pins, state and weight, and refuses to move it to another region") {
        val env = new Env
        val changed = cell.copy(state = CellState.Draining, weight = 0)
        for
          _ <- env.stored(cells = Vector(cell))
          _ <- env.repository.updateCell.succeedsWith(true)
          _ <- env.service.updateCell(changed)
          moved <- env.service.updateCell(changed.copy(regionId = astana.id)).either
          unknown <- env.service.updateCell(changed.copy(id = CellId("kz-9"))).either
          malformed <- env.service.updateCell(changed.copy(weight = -5)).either
        yield assertTrue(env.repository.updateCell.calls == List(changed), invalid(moved), missing(unknown), invalid(malformed))
      },
      test("only a closed cell is deleted: an open or a draining one still holds users") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        val draining = cell.copy(id = CellId("kz-3"), state = CellState.Draining)
        for
          _ <- env.stored(cells = Vector(cell, closed, draining))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(0L)
          _ <- env.moves.involvesUnfinished.succeedsWith(false)
          _ <- env.auth.departuresSummary.succeedsWith(summary(0))
          open <- env.service.deleteCell(cell.id, lost = false, force = false).either
          emptying <- env.service.deleteCell(draining.id, lost = false, force = false).either
          unknown <- env.service.deleteCell(CellId("kz-9"), lost = false, force = false).either
          _ <- env.service.deleteCell(closed.id, lost = false, force = false)
        yield assertTrue(
          conflict(open),
          conflict(emptying),
          missing(unknown),
          env.repository.deleteCell.calls == List(closed.id),
          // The state rule is first: a cell that is not closed is refused without the directory being asked.
          env.users.countInCell.calls == List(closed.id),
        )
      },
      test("a closed cell that the directory still places users in is refused with their number, and nothing is deleted") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        for
          _ <- env.stored(cells = Vector(closed))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(3L)
          result <- env.service.deleteCell(closed.id, lost = false, force = false).either
        yield assertTrue(
          conflict(result),
          result.left.exists(_.asInstanceOf[TopologyError].message.contains("3 user")),
          env.users.countInCell.calls == List(closed.id),
          env.repository.deleteCell.calls.isEmpty,
        )
      },
      test("a cell with a move from or to it that is not finished is refused, and nothing is deleted") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        for
          _ <- env.stored(cells = Vector(closed))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(0L)
          _ <- env.moves.involvesUnfinished.succeedsWith(true)
          result <- env.service.deleteCell(closed.id, lost = false, force = true).either
        yield assertTrue(
          conflict(result),
          env.moves.involvesUnfinished.calls == List(closed.id),
          // the cell is not asked about its tokens, and `force` does not lift this rule
          env.auth.departuresSummary.calls.isEmpty,
          env.repository.deleteCell.calls.isEmpty,
        )
      },
      test("a cell that still answers moved for refresh tokens is refused, unless force is given, which deletes it") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        for
          _ <- env.stored(cells = Vector(closed))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(0L)
          _ <- env.moves.involvesUnfinished.succeedsWith(false)
          _ <- env.auth.departuresSummary.succeedsWith(summary(4))
          refused <- env.service.deleteCell(closed.id, lost = false, force = false).either
          afterRefusal = env.repository.deleteCell.calls
          _ <- env.service.deleteCell(closed.id, lost = false, force = true)
        yield assertTrue(
          conflict(refused),
          refused.left.exists(_.asInstanceOf[TopologyError].message.contains("4 refresh token")),
          afterRefusal.isEmpty,
          env.repository.deleteCell.calls == List(closed.id),
        )
      },
      test("a cell that does not answer is not known to hold no tokens: refused unless force, or lost") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        for
          _ <- env.stored(cells = Vector(closed))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(0L)
          _ <- env.moves.involvesUnfinished.succeedsWith(false)
          _ <- env.auth.departuresSummary.failsWith(new RuntimeException("connection refused"))
          refused <- env.service.deleteCell(closed.id, lost = false, force = false).either
          forced <- env.service.deleteCell(closed.id, lost = false, force = true).either
          lost <- env.service.deleteCell(closed.id, lost = true, force = false).either
        yield assertTrue(conflict(refused), forced.isRight, lost.isRight, env.repository.deleteCell.calls == List(closed.id, closed.id))
      },
      test("a cell that is slow to answer is waited for no longer than the timeout and counts as not answering") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        for
          _ <- env.stored(cells = Vector(closed))
          _ <- env.users.countInCell.succeedsWith(0L)
          _ <- env.moves.involvesUnfinished.succeedsWith(false)
          _ <- env.auth.departuresSummary.returnsZIO(_ => ZIO.never)
          fiber <- env.service.deleteCell(closed.id, lost = false, force = false).either.fork
          _ <- TestClock.adjust(TopologyService.SummaryTimeout + 1.second)
          result <- fiber.join
        yield assertTrue(conflict(result))
      },
      test("a cell said to be lost is deleted with the users the directory places in it, and only when it is closed") {
        val env = new Env
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        val draining = cell.copy(id = CellId("kz-3"), state = CellState.Draining)
        for
          _ <- env.stored(cells = Vector(closed, draining))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(3L)
          emptying <- env.service.deleteCell(draining.id, lost = true, force = false).either
          _ <- env.service.deleteCell(closed.id, lost = true, force = false)
        yield assertTrue(
          conflict(emptying),
          env.repository.deleteCell.calls == List(closed.id),
          // the directory, the moves and the cell itself are not what decides here
          env.users.countInCell.calls.isEmpty,
          env.moves.involvesUnfinished.calls.isEmpty,
          env.auth.departuresSummary.calls.isEmpty,
        )
      },
      test("the status of a cell: its users, its unfinished moves, what it reports, and why a draining one is stuck") {
        val env = new Env
        val draining = cell.copy(id = CellId("kz-3"), state = CellState.Draining)
        val silent = cell.copy(id = CellId("kz-4"), regionId = astana.id)
        for
          _ <- env.stored(cells = Vector(draining, silent))
          _ <- env.users.countInCell.returnsZIO(id => ZIO.succeed(if id == draining.id then 7L else 2L))
          _ <- env.moves.unfinishedFromCounts.succeedsWith(Map(draining.id -> 3L))
          _ <-
            env.auth.departuresSummary.returnsZIO(id => if id == draining.id then ZIO.succeed(summary(5)) else ZIO.fail(new RuntimeException("down")))
          statuses <- env.service.cellStatuses
        yield assertTrue(
          statuses(draining.id) == CellStatus(users = 7, moving = 3, departures = Some(summary(5)), drainBlocked = None),
          // the cell that does not answer is not an error of the request, and a cell that is not draining is never "blocked"
          statuses(silent.id) == CellStatus(users = 2, moving = 0, departures = None, drainBlocked = None),
        )
      },
      test("a draining cell is blocked when no open cell of its partition can take its users") {
        val env = new Env
        val draining = cell.copy(id = CellId("kz-3"), state = CellState.Draining)
        val closed = cell.copy(id = CellId("kz-2"), state = CellState.Closed)
        val weightless = cell.copy(id = CellId("kz-5"), weight = 0)
        for
          _ <- env.stored(cells = Vector(draining, closed, weightless))
          _ <- env.users.countInCell.succeedsWith(1L)
          _ <- env.moves.unfinishedFromCounts.succeedsWith(Map.empty)
          _ <- env.auth.departuresSummary.succeedsWith(summary(0))
          statuses <- env.service.cellStatuses
        yield assertTrue(statuses(draining.id).drainBlocked.contains("no_open_cell"))
      },
    ),
    suite("the keys of a cell")(
      test("the keys are stored with the cell, one or two, and the cells in memory are brought up to date") {
        val env = new Env
        val two = cell.copy(publicKeys = List(key1, key2))
        for
          _ <- env.stored()
          _ <- env.repository.createCell.succeedsWith(true)
          _ <- env.service.createCell(cell)
          _ <- env.service.createCell(two)
          refreshed <- env.refreshed.get
        yield assertTrue(env.repository.createCell.calls == List(cell, two), refreshed == 2)
      },
      test("a cell is refused, and nothing is written, for each thing its keys may not be") {
        val env = new Env
        val withPrivate = json(RSAKeyGenerator(2048).keyID("with-private").generate())
        val refused = List(
          "no key" -> cell.copy(publicKeys = Nil),
          "three keys" -> cell.copy(publicKeys = List(key1, key2, publicRsa("third"))),
          "an EC key" -> cell.copy(publicKeys = List(json(ECKeyGenerator(Curve.P_256).keyID("ec").generate().toPublicJWK))),
          "a key with a private member" -> cell.copy(publicKeys = List(withPrivate)),
          "an RSA key under 2048 bits" -> cell.copy(publicKeys = List(json(RSAKeyGenerator(1024, true).keyID("small").generate().toPublicJWK))),
          "a key with no kid" -> cell.copy(publicKeys = List(Json.Obj(key1.fields.filterNot(_._1 == "kid")*))),
          "something that is not a JWK" -> cell.copy(publicKeys = List(Json.Obj("kty" -> Json.Str("nonsense")))),
          "a kid named twice" -> cell.copy(publicKeys = List(key1, renamed(key2, "kz-1-key-1"))),
          "a key named twice, under two kids" -> cell.copy(publicKeys = List(key1, renamed(key1, "another-name"))),
        )
        for
          _ <- env.stored()
          results <- ZIO.foreach(refused)((name, record) => env.service.createCell(record).either.map(name -> _))
          refreshed <- env.refreshed.get
        yield assertTrue(
          results.filterNot(result => invalid(result._2)).map(_._1).isEmpty,
          env.repository.createCell.calls.isEmpty,
          refreshed == 0,
        )
      },
      test("a key of another cell, or one of its kids, is refused: a call would be taken for the other cell's") {
        val env = new Env
        val other = cell.copy(id = CellId("kz-2"), publicKeys = List(otherCellKey))
        for
          _ <- env.stored(cells = Vector(other))
          sameKey <- env.service.createCell(cell.copy(publicKeys = List(renamed(otherCellKey, "mine")))).either
          sameKid <- env.service.createCell(cell.copy(publicKeys = List(renamed(key1, "kz-2-key")))).either
          _ <- env.repository.createCell.succeedsWith(true)
          own <- env.service.createCell(cell).either
        yield assertTrue(invalid(sameKey), invalid(sameKid), own.isRight, env.repository.createCell.calls == List(cell))
      },
      test("a key of an edge is refused") {
        val env = new Env
        for
          _ <- env.stored(registeredEdges = Vector(edge("edge-1", EdgeGroupKind.FirstParty, key = Some(edgeKey))))
          sameKey <- env.service.createCell(cell.copy(publicKeys = List(renamed(edgeKey, "mine")))).either
          _ <- env.repository.createCell.succeedsWith(true)
          own <- env.service.createCell(cell).either
        yield assertTrue(invalid(sameKey), own.isRight)
      },
      test("a key of the configured auth is refused for a cell, which is none of its own, and allowed for the cell it is configured as") {
        val configured = TestCentralConfig.authConfig.publicKeys.head
        val sharing = cell.copy(publicKeys = List(configured))
        val asCell1 = TestCentralConfig.config.copy(auth = TestCentralConfig.authConfig.copy(cellId = Some(cell.id)))
        val asCell2 = TestCentralConfig.config.copy(auth = TestCentralConfig.authConfig.copy(cellId = Some(CellId("kz-2"))))
        val env = new Env
        val itsCell = new Env(asCell1)
        val another = new Env(asCell2)
        for
          _ <- env.stored()
          _ <- itsCell.stored()
          _ <- another.stored()
          _ <- itsCell.repository.createCell.succeedsWith(true)
          unnamed <- env.service.createCell(sharing).either
          named <- another.service.createCell(sharing).either
          renamedKid <- env.service.createCell(cell.copy(publicKeys = List(renamed(key1, TestCentralConfig.authKeyPair.keyId)))).either
          itself <- itsCell.service.createCell(sharing).either
        yield assertTrue(invalid(unnamed), invalid(named), invalid(renamedKid), itself.isRight, itsCell.repository.createCell.calls == List(sharing))
      },
      test("an update replaces the keys, and may keep the ones the cell has: they are not another cell's") {
        val env = new Env
        val replaced = cell.copy(publicKeys = List(key1, key2))
        val next = cell.copy(publicKeys = List(key2))
        for
          _ <- env.stored(cells = Vector(cell))
          _ <- env.repository.updateCell.succeedsWith(true)
          _ <- env.service.updateCell(replaced)
          _ <- env.service.updateCell(next)
          refreshed <- env.refreshed.get
        yield assertTrue(env.repository.updateCell.calls == List(replaced, next), refreshed == 2)
      },
      test("an update is refused, and nothing is written, for a key that is none, too many, or another cell's") {
        val env = new Env
        val other = cell.copy(id = CellId("kz-2"), publicKeys = List(otherCellKey))
        for
          _ <- env.stored(cells = Vector(cell, other))
          none <- env.service.updateCell(cell.copy(publicKeys = Nil)).either
          many <- env.service.updateCell(cell.copy(publicKeys = List(key1, key2, publicRsa("third")))).either
          stolen <- env.service.updateCell(cell.copy(publicKeys = List(key1, renamed(otherCellKey, "mine")))).either
          refreshed <- env.refreshed.get
        yield assertTrue(invalid(none), invalid(many), invalid(stolen), env.repository.updateCell.calls.isEmpty, refreshed == 0)
      },
      test("a deleted cell stops verifying at once") {
        val env = new Env
        val closed = cell.copy(state = CellState.Closed)
        for
          _ <- env.stored(cells = Vector(closed))
          _ <- env.repository.deleteCell.succeedsWith(true)
          _ <- env.users.countInCell.succeedsWith(0L)
          _ <- env.moves.involvesUnfinished.succeedsWith(false)
          _ <- env.auth.departuresSummary.succeedsWith(summary(0))
          _ <- env.service.deleteCell(closed.id, lost = false, force = false)
          refreshed <- env.refreshed.get
        yield assertTrue(refreshed == 1)
      },
    ),
    suite("the region of an edge site")(
      test("one of the operator's own edges is put in a region that exists, and taken out of any") {
        val env = new Env
        for
          _ <- env.stored()
          _ <- env.edges.findFresh.succeedsWith(Some(edge("edge-almaty", EdgeGroupKind.FirstParty)))
          _ <- env.repository.setEdgeRegion.succeedsWith(true)
          _ <- env.service.setEdgeRegion(EdgeId("edge-almaty"), Some(almaty.id))
          _ <- env.service.setEdgeRegion(EdgeId("edge-almaty"), None)
        yield assertTrue(
          env.repository.setEdgeRegion.calls == List((EdgeId("edge-almaty"), Some(almaty.id)), (EdgeId("edge-almaty"), None)),
        )
      },
      test("a partner's edge, an edge that does not exist and a region that does not exist are refused, and nothing is written") {
        val env = new Env
        for
          _ <- env.stored()
          _ <- env.edges.findFresh.succeedsWith(Some(edge("edge-acme", EdgeGroupKind.Partner)))
          partner <- env.service.setEdgeRegion(EdgeId("edge-acme"), Some(almaty.id)).either
          _ <- env.edges.findFresh.succeedsWith(None)
          unknownEdge <- env.service.setEdgeRegion(EdgeId("edge-none"), Some(almaty.id)).either
          _ <- env.edges.findFresh.succeedsWith(Some(edge("edge-almaty", EdgeGroupKind.FirstParty)))
          unknownRegion <- env.service.setEdgeRegion(EdgeId("edge-almaty"), Some(RegionId("aktau"))).either
        yield assertTrue(invalid(partner), missing(unknownEdge), invalid(unknownRegion), env.repository.setEdgeRegion.calls.isEmpty)
      },
    ),
  )
