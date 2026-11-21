package versola.central.configuration.topology

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.edges.{EdgeGroup, EdgeGroupId, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.util.TestCertificates
import zio.*
import zio.test.*

object TopologyServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val kz = PartitionRecord(PartitionId("kz"))
  private val almaty = RegionRecord(RegionId("almaty"), kz.id)
  private val astana = RegionRecord(RegionId("astana"), kz.id)
  private val pin = TestCertificates.generate(subject = "CN=auth-kz-1").certificatePem

  private val cell = CellRecord(
    id = CellId("kz-1"),
    regionId = almaty.id,
    internalUrl = "http://auth-kz-1.internal:9003",
    mtlsUrl = Some("https://auth-kz-1.internal:9008"),
    trustedCertificates = List(pin),
    state = CellState.Open,
    weight = 100,
  )

  private def edge(id: String, kind: EdgeGroupKind) = EdgeRecord(
    id = EdgeId(id),
    publicKey = None,
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

  class Env:
    val repository = stub[TopologyRepository]
    val edges = stub[EdgeService]
    val service = TopologyService.Impl(repository, edges)

    def stored(
        regions: Vector[RegionRecord] = Vector(almaty, astana),
        cells: Vector[CellRecord] = Vector.empty,
        edgeRegions: Map[EdgeId, RegionId] = Map.empty,
    ): UIO[Unit] =
      repository.getPartitions.succeedsWith(Vector(kz)) *>
        repository.getRegions.succeedsWith(regions) *>
        repository.getCells.succeedsWith(cells) *>
        repository.getEdgeRegions.succeedsWith(edgeRegions)

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
          open <- env.service.deleteCell(cell.id).either
          emptying <- env.service.deleteCell(draining.id).either
          unknown <- env.service.deleteCell(CellId("kz-9")).either
          _ <- env.service.deleteCell(closed.id)
        yield assertTrue(conflict(open), conflict(emptying), missing(unknown), env.repository.deleteCell.calls == List(closed.id))
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
