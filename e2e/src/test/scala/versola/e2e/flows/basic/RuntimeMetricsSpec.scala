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

  /** What a deployment scales auth's replicas on once CPU stops following demand: Argon2id hashing
    * runs behind a semaphore, so the pod's CPU sits at what the permits admit while logins queue.
    * `AdmissionMetrics` is only worth anything if its names reach the scrape of a real process.
    */
  private val admission = List(
    "argon2_max_concurrent",
    "argon2_hashes_in_flight",
    "argon2_hashes_waiting",
    "argon2_hash_wait_seconds_count",
    "argon2_hash_duration_seconds_count",
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
    test("auth's /metrics reports the Argon2id admission control after a password login") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.probe(Method.GET, s"${auth.authDiagnosticsBaseUrl}/metrics")
        body <- response.body.asString
        missing = admission.filterNot(body.contains)
      yield assertTrue(missing.isEmpty)
        .label(s"/metrics is missing ${missing.mkString(", ")}")
    },
  )
