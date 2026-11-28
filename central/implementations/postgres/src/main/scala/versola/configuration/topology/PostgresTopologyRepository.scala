package versola.configuration.topology

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.EdgeId
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
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

class PostgresTopologyRepository(xa: TransactorZIO) extends TopologyRepository, BasicCodecs:
  given DbCodec[PartitionId] = DbCodec.StringCodec.biMap(PartitionId(_), identity[String])
  given DbCodec[RegionId] = DbCodec.StringCodec.biMap(RegionId(_), identity[String])
  given DbCodec[CellId] = DbCodec.StringCodec.biMap(CellId(_), identity[String])
  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[CellState] =
    DbCodec.StringCodec.biMap(code => CellState.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[List[Json.Obj]] = jsonBCodec[List[Json.Obj]]
  given DbCodec[PartitionRecord] = DbCodec.derived
  given DbCodec[RegionRecord] = DbCodec.derived
  given DbCodec[CellRecord] = DbCodec.derived

  override def getPartitions: Task[Vector[PartitionRecord]] =
    xa.connectMeasured("get-partitions"):
      sql"SELECT id FROM partitions ORDER BY id".query[PartitionRecord].run()

  override def createPartition(record: PartitionRecord): Task[Boolean] =
    xa.connectMeasured("create-partition"):
      sql"INSERT INTO partitions (id) VALUES (${record.id}) ON CONFLICT (id) DO NOTHING".update.run() > 0

  override def getRegions: Task[Vector[RegionRecord]] =
    xa.connectMeasured("get-regions"):
      sql"SELECT id, partition_id FROM regions ORDER BY id".query[RegionRecord].run()

  override def createRegion(record: RegionRecord): Task[Boolean] =
    xa.connectMeasured("create-region"):
      sql"""
        INSERT INTO regions (id, partition_id) VALUES (${record.id}, ${record.partitionId})
        ON CONFLICT (id) DO NOTHING
      """.update.run() > 0

  override def deleteRegion(id: RegionId): Task[Boolean] =
    xa.connectMeasured("delete-region"):
      sql"DELETE FROM regions WHERE id = $id".update.run() > 0

  override def getCells: Task[Vector[CellRecord]] =
    xa.connectMeasured("get-cells"):
      sql"""
        SELECT id, region_id, internal_url, mtls_url, trusted_certificates, state, weight, public_keys
        FROM cells ORDER BY id
      """.query[CellRecord].run()

  override def createCell(record: CellRecord): Task[Boolean] =
    xa.connectMeasured("create-cell"):
      sql"""
        INSERT INTO cells (id, region_id, internal_url, mtls_url, trusted_certificates, state, weight, public_keys)
        VALUES (${record.id}, ${record.regionId}, ${record.internalUrl}, ${record.mtlsUrl}, ${record.trustedCertificates},
                ${record.state}, ${record.weight}, ${record.publicKeys})
        ON CONFLICT (id) DO NOTHING
      """.update.run() > 0

  override def updateCell(record: CellRecord): Task[Boolean] =
    xa.connectMeasured("update-cell"):
      sql"""
        UPDATE cells
        SET internal_url = ${record.internalUrl}, mtls_url = ${record.mtlsUrl},
            trusted_certificates = ${record.trustedCertificates}, state = ${record.state}, weight = ${record.weight},
            public_keys = ${record.publicKeys}
        WHERE id = ${record.id}
      """.update.run() > 0

  override def deleteCell(id: CellId): Task[Boolean] =
    xa.connectMeasured("delete-cell"):
      sql"DELETE FROM cells WHERE id = $id".update.run() > 0

  override def getEdgeRegions: Task[Map[EdgeId, RegionId]] =
    xa.connectMeasured("get-edge-regions"):
      sql"SELECT id, region_id FROM edges WHERE region_id IS NOT NULL".query[(EdgeId, RegionId)].run().toMap

  override def setEdgeRegion(edgeId: EdgeId, regionId: Option[RegionId]): Task[Boolean] =
    xa.connectMeasured("set-edge-region"):
      sql"UPDATE edges SET region_id = $regionId WHERE id = $edgeId".update.run() > 0

object PostgresTopologyRepository:
  def live: ZLayer[TransactorZIO, Nothing, TopologyRepository] =
    ZLayer.fromFunction(PostgresTopologyRepository(_))
