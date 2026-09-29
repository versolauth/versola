package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.{Task, ZLayer}

class PostgresSutProcessSnapshotRepository(xa: TransactorZIO) extends SutProcessSnapshotRepository, StoreCodecs:

  private given DbCodec[SutProcessSnapshotRow] = DbCodec.derived

  override def append(snapshot: SutProcessSnapshotRow): Task[Unit] =
    xa.transactMeasured("append-sut-process-snapshot"):
      sql"""
        INSERT INTO vu_sut_process_snapshots (
          campaign, service, phase, captured_at, started_at_epoch_seconds, statistics
        ) VALUES (
          ${snapshot.campaign}, ${snapshot.service}, ${snapshot.phase}, ${snapshot.capturedAt},
          ${snapshot.startedAtEpochSeconds}, ${snapshot.statistics}
        )
        ON CONFLICT (campaign, service, phase) DO NOTHING
      """.update.run()
    .unit

  override def loadCampaign(campaign: String): Task[Vector[SutProcessSnapshotRow]] =
    xa.connectMeasured("load-campaign-sut-process-snapshots"):
      sql"""
        SELECT campaign, service, phase, captured_at, started_at_epoch_seconds, statistics
        FROM vu_sut_process_snapshots
        WHERE campaign = $campaign
        ORDER BY service, phase
      """.query[SutProcessSnapshotRow].run()

object PostgresSutProcessSnapshotRepository:
  def live: ZLayer[TransactorZIO, Throwable, SutProcessSnapshotRepository] =
    ZLayer.fromFunction(PostgresSutProcessSnapshotRepository(_))
