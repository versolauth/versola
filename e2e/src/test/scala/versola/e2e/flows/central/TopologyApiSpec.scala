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

  /** `key` is the public key of `cell`, `otherKey` another one; `otherCell` is a second cell, for the rules between two. */
  private case class Ids(
      partition: String,
      region: String,
      otherRegion: String,
      cell: String,
      otherCell: String,
      key: Json.Obj,
      otherKey: Json.Obj,
  )

  /** A public RSA JWK of a key of its own, 2048 bits, under a kid nobody else has. */
  private def publicKey(kid: String): UIO[Json.Obj] =
    CellIdentity.generate(kid).flatMap(_.publicJwk).orDie

  private def ids: UIO[Ids] =
    for
      partition <- CentralApi.id("e2e-partition")
      region <- CentralApi.id("e2e-region")
      otherRegion <- CentralApi.id("e2e-region")
      cell <- CentralApi.id("e2e-cell")
      otherCell <- CentralApi.id("e2e-cell")
      key <- publicKey(s"$cell-key")
      otherKey <- publicKey(s"$otherCell-key")
    yield Ids(partition, region, otherRegion, cell, otherCell, key, otherKey)

  private def cell(
      id: String,
      region: String,
      key: Json.Obj,
      state: String = "open",
      weight: Int = 100,
      mtlsUrl: Option[String] = None,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "id" -> Json.Str(id),
        "regionId" -> Json.Str(region),
        "internalUrl" -> Json.Str(s"http://$id.internal.example:9003"),
        "trustedCertificates" -> Json.Arr(),
        "state" -> Json.Str(state),
        "weight" -> Json.Num(weight),
        // A cell is registered with its public key (versolauth/versola#1050): central knows the cell that calls it by that key.
        "publicKeys" -> Json.Arr(key),
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
    (central.put("/configuration/cells", cell(ids.cell, ids.region, ids.key, state = "closed")) *>
      central.delete("/configuration/cells", "cellId" -> ids.cell, "force" -> "true") *>
      central.put("/configuration/cells", cell(ids.otherCell, ids.region, ids.otherKey, state = "closed")) *>
      central.delete("/configuration/cells", "cellId" -> ids.otherCell, "force" -> "true") *>
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
          _ <- created(central, "/configuration/cells", cell(ids.cell, ids.region, ids.key))
          listed <- cells(central)
          again <- central.post("/configuration/cells", cell(ids.cell, ids.region, ids.key))
          updated <- central.put("/configuration/cells", cell(ids.cell, ids.region, ids.key, state = "draining", weight = 0))
          afterUpdate <- cells(central)
          regionInUse <- central.delete("/configuration/regions", "regionId" -> ids.region)
          deletedWhileDraining <- central.delete("/configuration/cells", "cellId" -> ids.cell)
          closed <- central.put("/configuration/cells", cell(ids.cell, ids.region, ids.key, state = "closed", weight = 0))
          // the cell of this case has an address nothing answers at, so it cannot say what it still answers for
          unanswered <- central.delete("/configuration/cells", "cellId" -> ids.cell)
          deleted <- central.delete("/configuration/cells", "cellId" -> ids.cell, "force" -> "true")
          regionDeleted <- central.delete("/configuration/regions", "regionId" -> ids.region)
          afterDelete <- cells(central)
        yield assertTrue(listed.exists(c => c.str("id").contains(ids.cell) && c.str("state").contains("open")))
          .label("the cell is listed as it was registered") &&
          assertTrue(again.status == Status.Conflict).label("a second cell of the same id is refused") &&
          assertTrue(
            updated.status == Status.NoContent,
            afterUpdate.exists(c => c.str("id").contains(ids.cell) && c.str("state").contains("draining")),
          )
            .label("an update changes the state") &&
          assertTrue(regionInUse.status == Status.Conflict).label("a region with a cell in it is not deleted") &&
          assertTrue(deletedWhileDraining.status == Status.Conflict).label("a draining cell still holds users") &&
          assertTrue(closed.status == Status.NoContent, unanswered.status == Status.Conflict)
            .label("a closed cell that does not say whether it still answers for refresh tokens of moved users is kept") &&
          assertTrue(deleted.status == Status.NoContent).label("and deleted once the operator says so with force") &&
          assertTrue(regionDeleted.status == Status.NoContent).label("and then its region") &&
          assertTrue(!afterDelete.exists(_.str("id").contains(ids.cell)))).ensuring(cleanup(central, ids))
      yield result
    },
    test("a cell is refused in a region that does not exist, with a negative weight, and with a mutual-TLS address without TLS") {
      for
        central <- api
        ids <- ids
        result <- (for
          _ <- created(central, "/configuration/partitions", Json.Obj("id" -> Json.Str(ids.partition)))
          _ <- created(central, "/configuration/regions", region(ids.region, ids.partition))
          noRegion <- central.post("/configuration/cells", cell(ids.cell, ids.otherRegion, ids.key))
          negative <- central.post("/configuration/cells", cell(ids.cell, ids.region, ids.key, weight = -1))
          plainMtls <- central.post("/configuration/cells", cell(ids.cell, ids.region, ids.key, mtlsUrl = Some("http://cell.internal.example:9008")))
          noPartition <- central.post("/configuration/regions", region(ids.otherRegion, s"${ids.partition}-none"))
          listed <- cells(central)
        yield assertTrue(noRegion.status == Status.BadRequest).label("a region nobody registered") &&
          assertTrue(negative.status == Status.BadRequest).label("a negative weight") &&
          assertTrue(plainMtls.status == Status.BadRequest).label("a mutual-TLS listener is https") &&
          assertTrue(noPartition.status == Status.BadRequest).label("a region needs its partition") &&
          assertTrue(!listed.exists(_.str("id").contains(ids.cell))).label("nothing refused is stored")).ensuring(cleanup(central, ids))
      yield result
    },
    test("a cell is refused with no key, with a key that is not a public RSA key, and with a key another cell holds") {
      for
        central <- api
        ids <- ids
        result <- (for
          _ <- created(central, "/configuration/partitions", Json.Obj("id" -> Json.Str(ids.partition)))
          _ <- created(central, "/configuration/regions", region(ids.region, ids.partition))
          withoutKeys = Json.Obj(cell(ids.cell, ids.region, ids.key).fields.filterNot(_._1 == "publicKeys"))
          noField <- central.post("/configuration/cells", withoutKeys)
          noKey <- central.post("/configuration/cells", Json.Obj(withoutKeys.fields :+ ("publicKeys" -> Json.Arr())))
          private_ = Json.Obj(ids.key.fields :+ ("d" -> Json.Str("AQAB")))
          withPrivate <- central.post("/configuration/cells", Json.Obj(withoutKeys.fields :+ ("publicKeys" -> Json.Arr(private_))))
          withoutKid = Json.Obj(ids.key.fields.filterNot(_._1 == "kid"))
          noKid <- central.post("/configuration/cells", Json.Obj(withoutKeys.fields :+ ("publicKeys" -> Json.Arr(withoutKid))))
          three <- central.post(
            "/configuration/cells",
            Json.Obj(withoutKeys.fields :+ ("publicKeys" -> Json.Arr(
              ids.key,
              ids.otherKey,
              ids.key.copy(fields = ids.key.fields.map((k, v) => if k == "kid" then k -> Json.Str("third") else k -> v)),
            ))),
          )
          _ <- created(central, "/configuration/cells", cell(ids.cell, ids.region, ids.key))
          // the same key under another cell's id, and under a kid of its own: the key is what is compared, not its name
          sameKey <- central.post("/configuration/cells", cell(ids.otherCell, ids.region, ids.key))
          renamed = Json.Obj(ids.key.fields.map((k, v) => if k == "kid" then k -> Json.Str(s"${ids.otherCell}-renamed") else k -> v))
          sameKeyRenamed <- central.post("/configuration/cells", cell(ids.otherCell, ids.region, renamed))
          // another cell's key in a change of this one is refused too; its own key is not
          otherCreated <- central.post("/configuration/cells", cell(ids.otherCell, ids.region, ids.otherKey))
          stolen <- central.put("/configuration/cells", cell(ids.otherCell, ids.region, ids.key))
          unchanged <- central.put("/configuration/cells", cell(ids.cell, ids.region, ids.key, weight = 50))
          listed <- cells(central)
        yield assertTrue(noField.status == Status.BadRequest).label("a cell with no publicKeys") &&
          assertTrue(noKey.status == Status.BadRequest).label("a cell with an empty list of keys cannot call central") &&
          assertTrue(withPrivate.status == Status.BadRequest).label("a key with a private member") &&
          assertTrue(noKid.status == Status.BadRequest).label("a key without a kid") &&
          assertTrue(three.status == Status.BadRequest).label("at most two keys: the second only while a key is replaced") &&
          assertTrue(sameKey.status == Status.BadRequest).label("a key another cell holds") &&
          assertTrue(sameKeyRenamed.status == Status.BadRequest).label("the same key under another kid") &&
          assertTrue(otherCreated.status == Status.Created, stolen.status == Status.BadRequest).label("a change cannot take another cell's key") &&
          assertTrue(unchanged.status == Status.NoContent).label("a cell keeps its own key through a change") &&
          assertTrue(listed.count(c => c.str("id").exists(Set(ids.cell, ids.otherCell).contains)) == 2)
            .label("only the two cells that were accepted are stored")).ensuring(cleanup(central, ids))
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
          _ <- created(central, "/configuration/cells", cell(ids.cell, ids.region, ids.key))
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
          _ <- central.put("/configuration/edges/region", Json.Obj(), "edgeId" -> own.edgeId)
        yield assertTrue(
          placed.status == Status.NoContent,
          edges.exists(e => e.str("id").contains(own.edgeId) && e.str("regionId").contains(ids.region)),
        )
          .label("the edge is listed with its region") &&
          assertTrue(partnerPlaced.status == Status.BadRequest).label("a partner's edge is in no region") &&
          assertTrue(synced.status == Status.Ok, document.str("region").contains(ids.region)).label("the edge is told its own region") &&
          assertTrue(syncedCells.exists(c => c.str("id").contains(ids.cell) && c.str("partitionId").contains(ids.partition)))
            .label("and every cell with the partition of its region") &&
          assertTrue(refused.status == Status.Unauthorized).label("a partner's edge keeps one address and is not told the cells")).ensuring(cleanup(
          central,
          ids,
        ))
      yield result
    },
  )
