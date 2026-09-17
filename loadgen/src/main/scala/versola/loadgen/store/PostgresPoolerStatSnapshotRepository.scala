package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.{Task, ZLayer}

class PostgresPoolerStatSnapshotRepository(xa: TransactorZIO) extends PoolerStatSnapshotRepository, StoreCodecs:

  private given DbCodec[PoolerStatSnapshotRow] = DbCodec.derived

  override def append(snapshot: PoolerStatSnapshotRow): Task[Unit] =
    xa.transactMeasured("append-pooler-stat-snapshot"):
      sql"""
        INSERT INTO vu_pooler_stat_snapshots (
          campaign, pooler, phase, captured_at, version, statistics
        ) VALUES (
          ${snapshot.campaign}, ${snapshot.pooler}, ${snapshot.phase}, ${snapshot.capturedAt},
          ${snapshot.version}, ${snapshot.statistics}
        )
        ON CONFLICT (campaign, pooler, phase) DO NOTHING
      """.update.run()
    .unit

  override def loadCampaign(campaign: String): Task[Vector[PoolerStatSnapshotRow]] =
    xa.connectMeasured("load-campaign-pooler-stat-snapshots"):
      sql"""
        SELECT campaign, pooler, phase, captured_at, version, statistics
        FROM vu_pooler_stat_snapshots
        WHERE campaign = $campaign
        ORDER BY pooler, phase
      """.query[PoolerStatSnapshotRow].run()

object PostgresPoolerStatSnapshotRepository:
  def live: ZLayer[TransactorZIO, Throwable, PoolerStatSnapshotRepository] =
    ZLayer.fromFunction(PostgresPoolerStatSnapshotRepository(_))
