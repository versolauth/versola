package versola.loadgen.metrics

import zio.Duration
import zio.json.JsonCodec

import java.time.Instant

/** What a recorded latency belongs to. Two shapes, because §11 asks for two granularities and
  * they carry different labels: a step lives inside a scenario
  * (`loadgen_step_duration_seconds{scenario,step,outcome}`) while a flow is named on its own
  * (`loadgen_flow_duration_seconds{flow,outcome}`).
  *
  * Everything in here is a label value on the Prometheus side and a map key on the report side,
  * so it is bound by §11's cardinality budget: scenario/step/flow names come from configuration
  * and code, never from a user id, a session id or a parameterised path.
  */
enum MeasurementId derives JsonCodec:
  case Step(scenario: String, step: String)
  case Flow(flow: String)

/** Elapsed time from the *intended* start of an operation to its completion, which is the only
  * latency this package will record.
  *
  * The distinction is the whole point of the open-model design (design doc §6.3): with a
  * scheduled start time, a driver that falls behind reports the wait it imposed on itself as part
  * of the latency, instead of silently throttling and reporting a flattering number for the SUT.
  * A plain `Duration` would let a caller pass `completedAt - actuallyStartedAt` and lose that
  * correction with no visible symptom, so the type the recorder accepts can only be built by
  * naming the intended start (or by an explicit [[IntendedLatency.unsafe]] for the cases -- a
  * synthetic value in a test, a flow whose start is not scheduled -- where there is none).
  */
opaque type IntendedLatency = Duration

object IntendedLatency:
  def between(intendedStart: Instant, completedAt: Instant): IntendedLatency =
    val elapsed = Duration.fromInterval(intendedStart, completedAt)
    if elapsed.isNegative then Duration.Zero else elapsed

  def unsafe(elapsed: Duration): IntendedLatency = elapsed

  extension (latency: IntendedLatency)
    def toDuration: Duration = latency
    def micros: Long = latency.toNanos / 1000L
    def seconds: Double = latency.toNanos.toDouble / 1e9
