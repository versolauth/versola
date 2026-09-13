package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.{Chunk, Task, ZIO, ZLayer}

import java.time.Instant

class PostgresMetricSnapshotRepository(xa: TransactorZIO) extends MetricSnapshotRepository, StoreCodecs:

  private given DbCodec[MetricSnapshotRow] = DbCodec.derived

  override def appendAll(snapshots: Chunk[MetricSnapshotRow]): Task[Unit] =
    if snapshots.isEmpty then ZIO.unit
    else
      xa.transactMeasured("append-metric-snapshots"):
        batchUpdate(snapshots): snapshot =>
          sql"""
            INSERT INTO vu_metric_snapshots (
              campaign, driver_id, captured_at, wire_version, kind, scenario, name, unit,
              sample_count, histogram
            ) VALUES (
              ${snapshot.campaign}, ${snapshot.driverId}, ${snapshot.capturedAt},
              ${snapshot.wireVersion}, ${snapshot.kind}, ${snapshot.scenario}, ${snapshot.name},
              ${snapshot.unit}, ${snapshot.sampleCount}, ${snapshot.histogram}
            )
            ON CONFLICT (campaign, driver_id, captured_at, kind, COALESCE(scenario, ''), name)
            DO NOTHING
          """.update
      .unit

  override def loadCampaign(campaign: String, since: Instant): Task[Vector[MetricSnapshotRow]] =
    xa.connectMeasured("load-campaign-metric-snapshots"):
      sql"""
        SELECT campaign, driver_id, captured_at, wire_version, kind, scenario, name, unit,
               sample_count, histogram
        FROM vu_metric_snapshots
        WHERE campaign = $campaign AND captured_at >= $since
        ORDER BY captured_at, driver_id
      """.query[MetricSnapshotRow].run()

object PostgresMetricSnapshotRepository:
  def live: ZLayer[TransactorZIO, Throwable, MetricSnapshotRepository] =
    ZLayer.fromFunction(PostgresMetricSnapshotRepository(_))
