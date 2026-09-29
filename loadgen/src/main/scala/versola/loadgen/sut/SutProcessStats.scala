package versola.loadgen.sut

import zio.json.JsonCodec

/** One reading of one SUT *process*, scraped from the service's own `/metrics` at a campaign
  * boundary -- the other half of what a run costs, next to [[SutStats]]'s reading of what it
  * cost the database behind it.
  *
  * Why this is worth a bracket of its own. The report already states, exactly, how many
  * operations of each kind the campaign completed ([[versola.loadgen.metrics.LatencySummary]]'s
  * `count`) and what they cost Postgres, but nothing at all about the service in between. So the
  * one figure the whole sizing exercise exists to produce -- CPU seconds per login, per refresh,
  * per proxied action -- has to be assembled by hand afterwards from a Prometheus range query,
  * over a window chosen to approximate a campaign whose real boundaries only this coordinator
  * knows. Both halves of that division are already here; only the numerator was missing.
  *
  * Split into counters and gauges for [[SutStats]]'s reason and read the same way: a counter is
  * cumulative since the process started and means something only as a difference, a gauge is the
  * value at the instant of capture and differencing two of them produces a number with no name.
  */
case class SutProcessStats(counters: SutProcessCounters, gauges: SutProcessGauges) derives JsonCodec

/** The cumulative half, all of it since process start -- which is why
  * [[versola.loadgen.store.SutProcessSnapshotRow.startedAtEpochSeconds]] is a column of the
  * snapshot rather than a field here: it is this section's `stats_reset`.
  *
  * @param cpuSeconds
  *   `process_cpu_seconds_total`. The numerator of every cost-per-operation figure the campaign
  *   computes, and the reason the rest of this exists.
  * @param gcSeconds
  *   `jvm_gc_collection_seconds_sum`, summed over collectors. Part of [[cpuSeconds]], not an
  *   addition to it -- reported separately because "how much of the CPU went to GC" is the first
  *   question asked of a heap ceiling that turns out to be too low.
  * @param gcCollections
  *   `jvm_gc_collection_seconds_count`, summed the same way.
  */
case class SutProcessCounters(cpuSeconds: Double, gcSeconds: Double, gcCollections: Long) derives JsonCodec

/** The instantaneous half: what the process was holding when the boundary was taken.
  *
  * `heapUsedBytes` against `residentMemoryBytes` is what makes this worth scraping from the
  * process rather than reading from the container runtime -- cAdvisor's working set is the whole
  * container, heap and metaspace and code cache and thread stacks and direct buffers together,
  * and cannot answer which of them moved.
  */
case class SutProcessGauges(
    heapUsedBytes: Long,
    heapCommittedBytes: Long,
    nonHeapUsedBytes: Long,
    residentMemoryBytes: Long,
    threads: Long,
    openFileDescriptors: Long,
) derives JsonCodec
