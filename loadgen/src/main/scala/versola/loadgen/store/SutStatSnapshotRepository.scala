package versola.loadgen.store

import zio.Task

/** `vu_sut_stat_snapshots` (migration V0005): the pair of `pg_stat_*` readings the coordinator
  * takes from each SUT database at the campaign's boundaries (runbook 05-report-spec.md §3).
  *
  * Kept apart from [[MetricSnapshotRepository]] deliberately. That one holds HdrHistograms of the
  * *emulator's* observations, written every 60 s by every driver; this holds two rows per
  * database per campaign, written by the coordinator, about the system under test. Sharing a
  * table would mean one whose columns are meaningless for half its rows.
  */
trait SutStatSnapshotRepository:

  /** Records one boundary's reading. Re-capturing a boundary that already landed is a no-op
    * rather than a second row: which of two "before" readings the report happened to pick would
    * decide the campaign's numbers.
    */
  def append(snapshot: SutStatSnapshotRow): Task[Unit]

  /** Every reading recorded for one campaign, in capture order. Both phases of every database,
    * because pairing them is the report's job and a half-captured database has to be visible as
    * such rather than absent.
    */
  def loadCampaign(campaign: String): Task[Vector[SutStatSnapshotRow]]
