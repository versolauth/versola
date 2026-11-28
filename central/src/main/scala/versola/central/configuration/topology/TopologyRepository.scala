package versola.central.configuration.topology

import versola.central.configuration.edges.EdgeId
import zio.Task

/** Partitions, regions, cells and the region of each edge site (#557). Whether a write is allowed is the service's
  * to decide; a repository stores what it is given.
  */
trait TopologyRepository:
  def getPartitions: Task[Vector[PartitionRecord]]

  /** @return `false` when a partition with this id already exists. */
  def createPartition(record: PartitionRecord): Task[Boolean]

  def getRegions: Task[Vector[RegionRecord]]

  /** @return `false` when a region with this id already exists; its partition is not changed. */
  def createRegion(record: RegionRecord): Task[Boolean]

  /** @return `false` when there was no such region. */
  def deleteRegion(id: RegionId): Task[Boolean]

  def getCells: Task[Vector[CellRecord]]

  /** @return `false` when a cell with this id already exists; it is left as it was. */
  def createCell(record: CellRecord): Task[Boolean]

  /** Replaces everything but the id and the region of the cell. @return `false` when there is no such cell. */
  def updateCell(record: CellRecord): Task[Boolean]

  /** @return `false` when there was no such cell. */
  def deleteCell(id: CellId): Task[Boolean]

  /** The region of every edge that has one. */
  def getEdgeRegions: Task[Map[EdgeId, RegionId]]

  /** @return `false` when there is no such edge. */
  def setEdgeRegion(edgeId: EdgeId, regionId: Option[RegionId]): Task[Boolean]
