package versola.util.http

import zio.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus.{PrometheusPublisher, prometheusLayer, publisherLayer}
import zio.test.*

/** Pins the names [[VersolaApp.jvmRuntimeMetrics]] publishes, because the names are the contract.
  *
  * The campaign's cost-per-operation queries, the JVM dashboards and any alert on heap all select
  * by metric name; `DefaultJvmMetrics.live` and `.liveV2` differ in exactly that (`live` emits
  * `jvm_memory_bytes_used`, `liveV2` the `jvm_memory_used_bytes` the Prometheus conventions and
  * every off-the-shelf dashboard expect). Swapping one for the other compiles, starts, serves a
  * populated `/metrics`, and silently empties every panel -- so it is worth a test that fails.
  */
object JvmRuntimeMetricsSpec extends ZIOSpecDefault:

  /** The scrape, once the sampling schedule has produced a full reading.
    *
    * Polled rather than slept on. `JvmMetricsSchedule.default` collects on a fixed interval
    * instead of synchronously with layer construction, so the first scrape carries only the
    * static `jvm_info`; a fixed sleep would be either flaky or slower than it needs to be. The
    * memoized layer means both tests wait for this once, between them.
    */
  private val scrape: ZIO[PrometheusPublisher, Nothing, String] =
    ZIO
      .serviceWithZIO[PrometheusPublisher](_.get)
      .repeat(Schedule.spaced(200.millis) *> Schedule.identity[String].untilOutput(_.contains("process_cpu_seconds_total")))
      .timeout(30.seconds)
      .map(_.getOrElse(""))

  def spec = suite("VersolaApp.jvmRuntimeMetrics")(
    test("publishes process CPU, heap, GC and thread metrics under their v2 names") {
      for scraped <- scrape
      yield assertTrue(
        // The numerator of every cost-per-operation figure the sizing campaign computes; without
        // it CPU can only come from the container runtime, which sees the cgroup and not the
        // process in it.
        scraped.contains("process_cpu_seconds_total"),
        // Heap has no container-level equivalent at all -- cAdvisor reports the working set of
        // the whole container and cannot say how much of it is heap, which is the question
        // MaxRAMPercentage is tuned against.
        scraped.contains("jvm_memory_used_bytes"),
        scraped.contains("jvm_memory_committed_bytes"),
        scraped.contains("jvm_gc_collection_seconds"),
        scraped.contains("jvm_threads_current"),
      ).label(s"scraped metric names: ${scraped.linesIterator.take(40).mkString(", ")}")
    },
    // v1 spells these `jvm_memory_bytes_used` / `jvm_memory_bytes_committed`. Asserting their
    // absence is what makes the test above a check on the naming rather than just on the
    // presence of some memory metric: both names are "a heap gauge", only one is the one the
    // dashboards query.
    test("does not emit the v1 memory names") {
      for scraped <- scrape
      yield assertTrue(
        !scraped.contains("jvm_memory_bytes_used"),
        !scraped.contains("jvm_memory_bytes_committed"),
      )
    },
  ).provideShared(
    // Composed rather than listed: the sampling layer's value is its daemon fibers, not its
    // output, so nothing depends on it by type and a flat list lets ZIO prune it -- which is
    // also why VersolaApp wires it with `>+>` instead of adding it to a `provide`.
    ZLayer.succeed(MetricsConfig(100.millis)) >>>
      (publisherLayer >+> prometheusLayer >+> VersolaApp.jvmRuntimeMetrics),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
