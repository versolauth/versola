package versola.loadgen.store

import zio.Task

/** `vu_sut_process_snapshots` (migration V0007): the pair of `/metrics` readings the coordinator
  * takes from each SUT service at the campaign's boundaries.
  *
  * Apart from [[SutStatSnapshotRepository]] and [[PoolerStatSnapshotRepository]] for the reason
  * the migration gives: the three brackets run together but their rows share no identity.
  */
trait SutProcessSnapshotRepository:

  /** Records one boundary's reading. Re-capturing a boundary that already landed is a no-op, as
    * in [[SutStatSnapshotRepository.append]].
    */
  def append(snapshot: SutProcessSnapshotRow): Task[Unit]

  /** Every reading recorded for one campaign, in capture order. */
  def loadCampaign(campaign: String): Task[Vector[SutProcessSnapshotRow]]
