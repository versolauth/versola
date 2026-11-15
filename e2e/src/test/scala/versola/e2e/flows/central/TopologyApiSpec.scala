package versola.e2e.flows.central

import versola.e2e.support.PartnerFixture.*
import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Partitions, regions and cells on central's admin API (#557), and the cell list an edge syncs.
  *
  * Nothing routes by these yet, so what is proven here is the registry itself: what may be stored, what an edge
  * of the operator is told, and that a partner's edge is told nothing.
  */
object TopologyApiSpec extends CentralApiSpec:

  private case class Ids(partition: String, region: String, otherRegion: String, cell: String)

  private def ids: UIO[Ids] =
    for
      partition <- CentralApi.id("e2e-partition")
      region <- CentralApi.id("e2e-region")
      otherRegion <- CentralApi.id("e2e-region")
      cell <- CentralApi.id("e2e-cell")
    yield Ids(partition, region, otherRegion, cell)

  private def cell(id: String, region: String, state: String = "open", weight: Int = 100, mtlsUrl: Option[String] = None): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "id" -> Json.Str(id),
        "regionId" -> Json.Str(region),
        "internalUrl" -> Json.Str(s"http://$id.internal.example:9003"),
        "trustedCertificates" -> Json.Arr(),
        "state" -> Json.Str(state),
        "weight" -> Json.Num(weight),
      ) ++ Chunk.fromIterable(mtlsUrl.map(url => "mtlsUrl" -> Json.Str(url))),
    )

  private def region(id: String, partition: String): Json.Obj =
    Json.Obj("id" -> Json.Str(id), "partitionId" -> Json.Str(partition))

  private def created(central: CentralApi, path: String, body: Json.Obj): Task[Unit] =
    central.post(path, body).flatMap: result =>
      ZIO.fail(RuntimeException(s"POST $path refused: ${result.status} ${result.body}")).unless(result.status == Status.Created).unit

  private def cells(central: CentralApi): Task[Chunk[Json.Obj]] =
    central.get("/configuration/topology").flatMap(_.items("cells"))

  /** Removes what a test registered, in the order the rules allow: a cell closed and deleted, then its region. */
  private def cleanup(central: CentralApi, ids: Ids): UIO[Unit] =
    (central.put("/configuration/cells", cell(ids.cell, ids.region, state = "closed")) *>
      central.delete("/configuration/cells", "cellId" -> ids.cell) *>
      central.delete("/configuration/regions", "regionId" -> ids.region) *>
      central.delete("/configuration/regions", "regionId" -> ids.otherRegion)).ignore

  def spec = suite("Central API: topology")(
    test("a partition, a region and a cell are registered, read back, changed and removed, in the order the rules allow") {
      for
        central <- api
        ids <- ids
        result <- (for
          _ <- created(central, "/configuration/partitions", Json.Obj("id" -> Json.Str(ids.partition)))
          _ <- created(central, "/configuration/regions", region(ids.region, ids.partition))
          _ <- created(central, "/configuration/cells", cell(ids.cell, ids.region))
          listed <- cells(central)
          again <- central.post("/configuration/cells", cell(ids.cell, ids.region))
          updated <- central.put("/configuration/cells", cell(ids.cell, ids.region, state = "draining", weight = 0))
          afterUpdate <- cells(central)
          regionInUse <- central.delete("/configuration/regions", "regionId" -> ids.region)
          deletedWhileDraining <- central.delete("/configuration/cells", "cellId" -> ids.cell)
          closed <- central.put("/configuration/cells", cell(ids.cell, ids.region, state = "closed", weight = 0))
          deleted <- central.delete("/configuration/cells", "cellId" -> ids.cell)
          regionDeleted <- central.delete("/configuration/regions", "regionId" -> ids.region)
          afterDelete <- cells(central)
        yield assertTrue(listed.exists(c => c.str("id").contains(ids.cell) && c.str("state").contains("open")))
          .label("the cell is listed as it was registered") &&
          assertTrue(again.status == Status.Conflict).label("a second cell of the same id is refused") &&
          assertTrue(updated.status == Status.NoContent, afterUpdate.exists(c => c.str("id").contains(ids.cell) && c.str("state").contains("draining")))
            .label("an update changes the state") &&
          assertTrue(regionInUse.status == Status.Conflict).label("a region with a cell in it is not deleted") &&
          assertTrue(deletedWhileDraining.status == Status.Conflict).label("a draining cell still holds users") &&
          assertTrue(closed.status == Status.NoContent, deleted.status == Status.NoContent).label("a closed cell is deleted") &&
          assertTrue(regionDeleted.status == Status.NoContent).label("and then its region") &&
          assertTrue(!afterDelete.exists(_.str("id").contains(ids.cell)))
        ).ensuring(cleanup(central, ids))
      yield result
    },
    test("a cell is refused in a region that does not exist, with a negative weight, and with a mutual-TLS address without TLS") {
      for
        central <- api
        ids <- ids
        result <- (for
          _ <- created(central, "/configuration/partitions", Json.Obj("id" -> Json.Str(ids.partition)))
          _ <- created(central, "/configuration/regions", region(ids.region, ids.partition))
          noRegion <- central.post("/configuration/cells", cell(ids.cell, ids.otherRegion))
          negative <- central.post("/configuration/cells", cell(ids.cell, ids.region, weight = -1))
          plainMtls <- central.post("/configuration/cells", cell(ids.cell, ids.region, mtlsUrl = Some("http://cell.internal.example:9008")))
          noPartition <- central.post("/configuration/regions", region(ids.otherRegion, s"${ids.partition}-none"))
          listed <- cells(central)
        yield assertTrue(noRegion.status == Status.BadRequest).label("a region nobody registered") &&
          assertTrue(negative.status == Status.BadRequest).label("a negative weight") &&
          assertTrue(plainMtls.status == Status.BadRequest).label("a mutual-TLS listener is https") &&
          assertTrue(noPartition.status == Status.BadRequest).label("a region needs its partition") &&
          assertTrue(!listed.exists(_.str("id").contains(ids.cell))).label("nothing refused is stored")
        ).ensuring(cleanup(central, ids))
      yield result
    },
    test("an edge of the operator is put in a region and syncs the cells with its own region; a partner's edge is refused them") {
      for
        central <- api
        ids <- ids
        tenantId <- CentralApi.id("e2e-tenant")
        result <- (for
          _ <- created(central, "/configuration/partitions", Json.Obj("id" -> Json.Str(ids.partition)))
          _ <- created(central, "/configuration/regions", region(ids.region, ids.partition))
          _ <- created(central, "/configuration/cells", cell(ids.cell, ids.region))
          own <- createFirstParty(central, tenantId)
          partner <- createPartner(central, tenantId)
          placed <- central.put("/configuration/edges/region", Json.Obj("regionId" -> Json.Str(ids.region)), "edgeId" -> own.edgeId)
          partnerPlaced <- central.put("/configuration/edges/region", Json.Obj("regionId" -> Json.Str(ids.region)), "edgeId" -> partner.edgeId)
          edges <- central.get("/configuration/edges").flatMap(_.items("edges"))
          // The cell list is read from the tables, so the edge sees the cell as soon as central authenticates it.
          synced <- eventually(onSurface(central, own).get("/edge/sync/cells"))(_.status == Status.Ok)
          document <- synced.obj
          syncedCells <- synced.items("cells")
          refused <- onSurface(central, partner).get("/edge/sync/cells")
          onMainListener <- central.withBearer(own.token).get("/configuration/cells/sync")
          _ <- central.put("/configuration/edges/region", Json.Obj(), "edgeId" -> own.edgeId)
        yield assertTrue(placed.status == Status.NoContent, edges.exists(e => e.str("id").contains(own.edgeId) && e.str("regionId").contains(ids.region)))
          .label("the edge is listed with its region") &&
          assertTrue(partnerPlaced.status == Status.BadRequest).label("a partner's edge is in no region") &&
          assertTrue(synced.status == Status.Ok, document.str("region").contains(ids.region)).label("the edge is told its own region") &&
          assertTrue(syncedCells.exists(c => c.str("id").contains(ids.cell) && c.str("partitionId").contains(ids.partition)))
            .label("and every cell with the partition of its region") &&
          assertTrue(refused.status == Status.Unauthorized).label("a partner's edge keeps one address and is not told the cells") &&
          assertTrue(onMainListener.status == Status.Forbidden).label("the list auth syncs is not an edge's to read")
        ).ensuring(cleanup(central, ids))
      yield result
    },
  )
