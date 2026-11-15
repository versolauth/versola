package versola.central.configuration.topology

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeGroup, EdgeId, EdgeRecord, EdgeRepository}
import versola.util.DatabaseSpecBase
import zio.test.*

trait TopologyRepositorySpec extends DatabaseSpecBase[TopologyRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val kz = PartitionRecord(PartitionId("topology-spec-kz"))
  val almaty = RegionRecord(RegionId("topology-spec-almaty"), kz.id)
  val astana = RegionRecord(RegionId("topology-spec-astana"), kz.id)

  val cell = CellRecord(
    id = CellId("topology-spec-1"),
    regionId = almaty.id,
    internalUrl = "http://auth-1.internal:9003",
    mtlsUrl = Some("https://auth-1.internal:9008"),
    trustedCertificates = List("PIN-A", "PIN-B"),
    state = CellState.Open,
    weight = 100,
  )

  private def edge(env: TopologyRepositorySpec.Env, id: EdgeId) =
    env.edges
      .createEdge(
        id = id,
        publicKeyJwk = None,
        requireDpopNonce = EdgeRecord.DefaultRequireDpopNonce,
        groupId = EdgeGroup.DefaultId,
        publicUrl = None,
      )
      .mapError:
        case failure: Throwable => failure
        case other => RuntimeException(other.toString)

  override def testCases(env: TopologyRepositorySpec.Env) =
    List(
      test("a partition and a region are created once: a second creation reports the one that exists and changes nothing") {
        for
          first <- env.repository.createPartition(kz)
          again <- env.repository.createPartition(kz)
          region <- env.repository.createRegion(almaty)
          // The same id under another partition: the stored region keeps its own.
          other <- env.repository.createPartition(PartitionRecord(PartitionId("topology-spec-other")))
          regionAgain <- env.repository.createRegion(almaty.copy(partitionId = PartitionId("topology-spec-other")))
          partitions <- env.repository.getPartitions
          regions <- env.repository.getRegions
        yield assertTrue(
          first,
          !again,
          region,
          other,
          !regionAgain,
          partitions.filter(_.id.startsWith("topology-spec-")).map(_.id) == Vector(kz.id, PartitionId("topology-spec-other")),
          regions.filter(_.id.startsWith("topology-spec-")) == Vector(almaty),
        )
      },
      test("a cell is stored as it was given, pins in their order, and an update replaces everything but its id and region") {
        val changed = cell.copy(
          regionId = astana.id,
          internalUrl = "http://auth-1b.internal:9003",
          mtlsUrl = None,
          trustedCertificates = List("PIN-C"),
          state = CellState.Draining,
          weight = 0,
        )
        for
          _ <- env.repository.createPartition(kz)
          _ <- env.repository.createRegion(almaty)
          _ <- env.repository.createRegion(astana)
          created <- env.repository.createCell(cell)
          again <- env.repository.createCell(cell.copy(weight = 1))
          stored <- env.repository.getCells
          updated <- env.repository.updateCell(changed)
          afterUpdate <- env.repository.getCells
          missing <- env.repository.updateCell(changed.copy(id = CellId("topology-spec-none")))
        yield assertTrue(
          created,
          !again,
          stored.filter(_.id == cell.id) == Vector(cell),
          updated,
          afterUpdate.filter(_.id == cell.id) == Vector(changed.copy(regionId = almaty.id)),
          !missing,
        )
      },
      test("a cell and a region are deleted once, and a deletion of what is not there says so") {
        for
          _ <- env.repository.createPartition(kz)
          _ <- env.repository.createRegion(almaty)
          _ <- env.repository.createCell(cell)
          cellDeleted <- env.repository.deleteCell(cell.id)
          cellAgain <- env.repository.deleteCell(cell.id)
          regionDeleted <- env.repository.deleteRegion(almaty.id)
          regionAgain <- env.repository.deleteRegion(almaty.id)
          cells <- env.repository.getCells
          regions <- env.repository.getRegions
        yield assertTrue(
          cellDeleted,
          !cellAgain,
          regionDeleted,
          !regionAgain,
          !cells.exists(_.id == cell.id),
          !regions.exists(_.id == almaty.id),
        )
      },
      test("an edge is put in a region and taken out of it, and only the edges in a region are listed") {
        val site = EdgeId("topology-spec-edge-1")
        val unplaced = EdgeId("topology-spec-edge-2")
        for
          _ <- env.repository.createPartition(kz)
          _ <- env.repository.createRegion(almaty)
          _ <- edge(env, site)
          _ <- edge(env, unplaced)
          placed <- env.repository.setEdgeRegion(site, Some(almaty.id))
          unknown <- env.repository.setEdgeRegion(EdgeId("topology-spec-nowhere"), Some(almaty.id))
          regions <- env.repository.getEdgeRegions
          removed <- env.repository.setEdgeRegion(site, None)
          afterRemoval <- env.repository.getEdgeRegions
        yield assertTrue(
          placed,
          !unknown,
          regions.get(site) == Some(almaty.id),
          !regions.contains(unplaced),
          removed,
          !afterRemoval.contains(site),
        )
      },
    )

object TopologyRepositorySpec:
  /** `edges` writes the edges a case about the region of an edge site needs. */
  case class Env(repository: TopologyRepository, edges: EdgeRepository)
