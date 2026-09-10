package versola.loadgen.store

import zio.{Chunk, Task}

import java.time.Instant

/** `vu_metric_snapshots` (migration V0004): the latency snapshots a driver publishes every 60 s
  * (dev spec §11) and the coordinator merges into the campaign report (§12).
  *
  * Not on the write-behind path, unlike everything else a driver records. A dropped snapshot is
  * not lost bookkeeping, it is a hole in the measurement the whole campaign exists to produce,
  * so this is written on its own schedule and awaited.
  */
trait MetricSnapshotRepository:

  /** One snapshot interval's histograms, as one batch. Re-writing a snapshot that already
    * landed is a no-op rather than a duplicate: a retry after a failed write must not
    * double-count its buckets into the merge (`vu_metric_snapshots_identity_idx`).
    */
  def appendAll(snapshots: Chunk[MetricSnapshotRow]): Task[Unit]

  /** Everything recorded for one campaign at or after `since`, oldest interval first --
    * `GET /report/{campaign}`'s input. `since` is what makes an incremental merge possible
    * instead of re-reading a multi-hour campaign on every request.
    */
  def loadCampaign(campaign: String, since: Instant): Task[Vector[MetricSnapshotRow]]
