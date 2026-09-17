package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.{Task, ZLayer}

class PostgresSutStatSnapshotRepository(xa: TransactorZIO) extends SutStatSnapshotRepository, StoreCodecs:

  private given DbCodec[SutStatSnapshotRow] = DbCodec.derived

  override def append(snapshot: SutStatSnapshotRow): Task[Unit] =
    xa.transactMeasured("append-sut-stat-snapshot"):
      sql"""
        INSERT INTO vu_sut_stat_snapshots (
          campaign, database, phase, captured_at, server_version_num, stats_reset_at,
          wal_stats_reset_at, statistics
        ) VALUES (
          ${snapshot.campaign}, ${snapshot.database}, ${snapshot.phase}, ${snapshot.capturedAt},
          ${snapshot.serverVersionNum}, ${snapshot.statsResetAt}, ${snapshot.walStatsResetAt},
          ${snapshot.statistics}
        )
        ON CONFLICT (campaign, database, phase) DO NOTHING
      """.update.run()
    .unit

  override def loadCampaign(campaign: String): Task[Vector[SutStatSnapshotRow]] =
    xa.connectMeasured("load-campaign-sut-stat-snapshots"):
      sql"""
        SELECT campaign, database, phase, captured_at, server_version_num, stats_reset_at,
               wal_stats_reset_at, statistics
        FROM vu_sut_stat_snapshots
        WHERE campaign = $campaign
        ORDER BY database, phase
      """.query[SutStatSnapshotRow].run()

object PostgresSutStatSnapshotRepository:
  def live: ZLayer[TransactorZIO, Throwable, SutStatSnapshotRepository] =
    ZLayer.fromFunction(PostgresSutStatSnapshotRepository(_))
