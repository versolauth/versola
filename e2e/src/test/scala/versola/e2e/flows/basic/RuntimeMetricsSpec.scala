package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.http.{Method, Status}
import zio.test.*

/** `/metrics` on a running auth carries the JVM's own runtime metrics, not only the
  * application's counters.
  *
  * The unit test ([[versola.util.http.JvmRuntimeMetricsSpec]]) proves the layer publishes these
  * names. It cannot prove they reach the endpoint: the sampling layer's value is its daemon
  * fibers rather than its output, so nothing depends on it by type and ZIO will happily prune it
  * out of a wiring that lists it instead of composing it -- a build that compiles, starts, and
  * serves a `/metrics` that looks populated because the application's own counters are still
  * there. Only a scrape of a real process distinguishes those two.
  */
object RuntimeMetricsSpec extends E2ESpec:

  /** Names the sizing campaign's cost-per-operation queries select by. CPU is the numerator of
    * every such figure, and heap has no container-level equivalent at all -- cAdvisor reports
    * the whole container's working set and cannot say how much of it is heap.
    */
  private val required = List(
    "process_cpu_seconds_total",
    "jvm_memory_used_bytes",
    "jvm_memory_committed_bytes",
    "jvm_gc_collection_seconds",
    "jvm_threads_current",
  )

  def spec = suite("Runtime metrics")(
    test("auth's diagnostics endpoint publishes JVM runtime metrics alongside application ones") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.probe(Method.GET, s"${auth.authDiagnosticsBaseUrl}/metrics")
        body <- response.body.asString
        missing = required.filterNot(body.contains)
      yield assertTrue(response.status == Status.Ok)
        .label(s"/metrics must answer 200 on the diagnostics port, got ${response.status}") &&
        assertTrue(missing.isEmpty)
          .label(s"/metrics is missing ${missing.mkString(", ")}")
    },
  )
