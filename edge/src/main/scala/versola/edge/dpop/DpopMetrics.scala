package versola.edge.dpop

import zio.UIO
import zio.metrics.Metric

/** Visibility into the one way the replay guard degrades silently.
  *
  * Every other outcome is already visible: a rejected proof is a failed request. Falling back
  * to the local ring is not -- requests keep succeeding, and the only thing lost is the
  * cross-replica half of the check. This counter is the difference between an exposure someone
  * is paged about and one nobody notices until it is found in a log.
  */
object DpopMetrics:

  private val sharedRingFallbacks = Metric.counter("dpop_shared_ring_fallbacks_total")

  private val localRingCapacityHits = Metric.counter("dpop_local_ring_capacity_hits_total")

  /** A proof the local ring could not settle, answered without the fleet-wide record because
    * that record was unreachable. Any sustained non-zero rate means the cross-replica window
    * is open.
    */
  val sharedRingUnavailable: UIO[Unit] = sharedRingFallbacks.increment

  /** A digest the local ring's slot had no room left for, at `DpopReplayGuard.MaxSlotEntries`.
    * Correctness doesn't depend on this ring succeeding -- the fleet-wide record still settles
    * it -- but a sustained non-zero rate means the local fast path is being skipped for real
    * traffic and every one of those proofs is now paying the round trip this ring exists to
    * save.
    */
  val localRingAtCapacity: UIO[Unit] = localRingCapacityHits.increment
