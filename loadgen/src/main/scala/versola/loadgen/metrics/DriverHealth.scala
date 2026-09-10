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
  * `cpuUtilisation` is optional because the JVM genuinely cannot always answer -- `getProcessCpuLoad`
  * returns a negative value until it has two samples to difference. A `0.0` there would read as a
  * perfectly idle driver, which is the one answer that must not be guessed.
  */
case class DriverHealthSample(
    scheduleLagByScenario: Map[String, Duration],
    busyUsers: Int,
    inflightRequests: Int,
    storeFlushDroppedTotal: Long,
    cpuUtilisation: Option[Double],
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
  ): DriverHealthSource =
    new DriverHealthSource:
      override def sample: UIO[DriverHealthSample] =
        for
          lag <- scheduleLagByScenario
          busy <- busyUsers
          inflight <- inflightRequests
          dropped <- storeFlushDroppedTotal
          cpu <- ProcessCpu.utilisation
        yield DriverHealthSample(lag, busy, inflight, dropped, cpu)

/** Process CPU load from the JVM's own platform bean.
  *
  * Deliberately the *process* load, not the machine's: a driver pod shares its node with other
  * drivers, and the number the definition of done is stated against ("driver CPU < 40%") is this
  * driver's share of its own allocation.
  */
object ProcessCpu:

  val utilisation: UIO[Option[Double]] =
    ZIO.succeed:
      java.lang.management.ManagementFactory.getOperatingSystemMXBean match
        case bean: com.sun.management.OperatingSystemMXBean =>
          val load = bean.getProcessCpuLoad
          if load < 0.0 then None else Some(math.min(load, 1.0))
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
      _ <- ZIO.foreachDiscard(sample.cpuUtilisation)(LoadgenMetrics.driverCpu)
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
