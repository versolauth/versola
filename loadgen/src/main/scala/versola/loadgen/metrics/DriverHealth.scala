package versola.loadgen.metrics

import zio.{Duration, Ref, Schedule, UIO, ZIO}

/** One reading of "is this driver still a trustworthy instrument".
  *
  * Nothing here describes the SUT. Every field is a way the emulator can quietly stop measuring
  * what it claims to measure: schedule lag means the arrival process is behind and the load being
  * applied is not the load that was planned; dropped write-behind rows mean session state was
  * lost, which resurfaces later as a broken virtual user; CPU past ~40% means the client's own
  * queueing is inside the latencies it is reporting (design doc §6.5 sizes the fleet at 30% CPU
  * precisely so this cannot happen).
  *
  * `cpuRatio` is optional because it takes two readings to difference before there is an answer
  * at all -- see [[ProcessCpu]]. A `0.0` in the meantime would read as a perfectly idle driver,
  * which is the one answer that must not be guessed.
  */
case class DriverHealthSample(
    scheduleLagByScenario: Map[String, Duration],
    busyUsers: Int,
    inflightRequests: Int,
    storeFlushDroppedTotal: Long,
    cpuRatio: Option[Double],
)

/** This package's side of the driver-health contract.
  *
  * The two readings §11 needs are produced by packages this track does not own -- the scheduler
  * measures its own lag against intended start times, and the write-behind buffer counts its own
  * dropped rows -- so they arrive as effects to be pulled, not as a dependency to be imported.
  * `metrics` owns only the CPU reading and the publishing.
  */
trait DriverHealthSource:
  def sample: UIO[DriverHealthSample]

object DriverHealthSource:

  /** Assembles a source from the sibling packages' readings. This is the one-line wiring point:
    * each parameter is a read of a value the owning package already keeps, and none of them needs
    * to know this package exists.
    */
  def from(
      scheduleLagByScenario: UIO[Map[String, Duration]],
      busyUsers: UIO[Int],
      inflightRequests: UIO[Int],
      storeFlushDroppedTotal: UIO[Long],
  ): UIO[DriverHealthSource] =
    ProcessCpu.make.map: processCpu =>
      new DriverHealthSource:
        override def sample: UIO[DriverHealthSample] =
          for
            lag <- scheduleLagByScenario
            busy <- busyUsers
            inflight <- inflightRequests
            dropped <- storeFlushDroppedTotal
            cpu <- processCpu.ratio
          yield DriverHealthSample(lag, busy, inflight, dropped, cpu)

/** This driver's CPU use as a fraction of **its own allocation**, which is what the definition of
  * done's "driver CPU < 40%" is stated against.
  *
  * Not `getProcessCpuLoad`, despite the name. That returns the process's use as a fraction of all
  * host CPUs: on a 64-core node with a 2-core cgroup quota, a driver pegged at 100% of its quota
  * reports about 0.03. The gate would then never fire, and the one check that proves the
  * instrument was not itself the bottleneck would silently always pass -- which is exactly the
  * class of failure this track exists to catch.
  *
  * Computed from CPU-time deltas against the allocation instead:
  * {{{ratio = ΔprocessCpuTime / (ΔwallClock × availableProcessors)}}}
  * `availableProcessors` is cgroup-aware under `UseContainerSupport`, so it already equals the
  * quota rather than the node's core count.
  */
final class ProcessCpu private (previous: Ref[Option[ProcessCpu.Reading]]):

  /** `None` until there are two readings to difference -- the same reason the bean's own load is
    * negative at first. A `0.0` there would read as a perfectly idle driver, which is the one
    * answer that must not be guessed.
    *
    * Also `None` if the clock has not advanced between two calls, since the ratio would divide by
    * zero. Clamped to 1.0 at the top: rounding between two clocks sampled a few nanoseconds apart
    * can put a fully busy process marginally over.
    */
  def ratio: UIO[Option[Double]] =
    ProcessCpu.read.flatMap:
      case None => ZIO.none
      case Some(current) =>
        previous.modify: before =>
          val computed = before.flatMap: earlier =>
            val cpuNanos = (current.cpuTimeNanos - earlier.cpuTimeNanos).toDouble
            val wallNanos = (current.wallNanos - earlier.wallNanos).toDouble
            val capacity = wallNanos * ProcessCpu.availableProcessors
            if capacity <= 0.0 || cpuNanos < 0.0 then None
            else Some(math.min(cpuNanos / capacity, 1.0))
          (computed, Some(current))

object ProcessCpu:

  private[metrics] final case class Reading(cpuTimeNanos: Long, wallNanos: Long)

  val make: UIO[ProcessCpu] =
    Ref.make(Option.empty[Reading]).map(ProcessCpu(_))

  private def availableProcessors: Int =
    Runtime.getRuntime.availableProcessors

  private val read: UIO[Option[Reading]] =
    ZIO.succeed:
      java.lang.management.ManagementFactory.getOperatingSystemMXBean match
        case bean: com.sun.management.OperatingSystemMXBean =>
          val cpuTime = bean.getProcessCpuTime
          if cpuTime < 0L then None else Some(Reading(cpuTime, java.lang.System.nanoTime()))
        case _ => None

/** Publishes [[DriverHealthSample]]s onto the Prometheus surface on a timer. */
final class DriverHealthReporter private (source: DriverHealthSource, lastFlushDropped: Ref[Long]):

  def publish: UIO[Unit] =
    for
      sample <- source.sample
      _ <- ZIO.foreachDiscard(sample.scheduleLagByScenario) { case (scenario, lag) =>
        LoadgenMetrics.scheduleLag(scenario, lag)
      }
      _ <- LoadgenMetrics.busyUsers(sample.busyUsers)
      _ <- LoadgenMetrics.inflightRequests(sample.inflightRequests)
      _ <- flushDroppedDelta(sample.storeFlushDroppedTotal).flatMap(LoadgenMetrics.storeFlushDropped)
      _ <- ZIO.foreachDiscard(sample.cpuRatio)(LoadgenMetrics.driverCpu)
    yield ()

  def run(interval: Duration): UIO[Unit] =
    publish.repeat(Schedule.spaced(interval)).unit

  /** The source's figure is cumulative and the metric is a counter, so only the increase is
    * added. A decrease means the source's own counter restarted (a reconnect, a re-created
    * buffer), and the honest response is to add nothing and re-baseline -- a Prometheus counter
    * cannot go down, and pretending the drop was progress would invent dropped rows.
    */
  private def flushDroppedDelta(cumulative: Long): UIO[Long] =
    lastFlushDropped.modify: previous =>
      if cumulative >= previous then (cumulative - previous, cumulative) else (0L, cumulative)

object DriverHealthReporter:
  def make(source: DriverHealthSource): UIO[DriverHealthReporter] =
    Ref.make(0L).map(DriverHealthReporter(source, _))
