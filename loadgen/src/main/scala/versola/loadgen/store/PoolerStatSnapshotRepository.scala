package versola.loadgen.store

import zio.Task

/** `vu_pooler_stat_snapshots` (migration V0006): the pair of admin console readings the
  * coordinator takes from each PgBouncer at the campaign's boundaries (runbook 05-report-spec.md
  * §4).
  *
  * Apart from [[SutStatSnapshotRepository]] for the reason the migration gives: the two brackets
  * run together but their rows share no identity and no reset semantics.
  */
trait PoolerStatSnapshotRepository:

  /** Records one boundary's reading. Re-capturing a boundary that already landed is a no-op, as
    * in [[SutStatSnapshotRepository.append]].
    */
  def append(snapshot: PoolerStatSnapshotRow): Task[Unit]

  /** Every reading recorded for one campaign, in capture order. */
  def loadCampaign(campaign: String): Task[Vector[PoolerStatSnapshotRow]]
