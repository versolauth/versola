package versola.loadgen.scenario

import versola.loadgen.model.Platform
import zio.{Ref, UIO, ZIO}

/** How many arrivals this driver has actually executed, split by the platform of the user each
  * one landed on.
  *
  * The coordinator's achieved rate is a difference of two of these readings
  * (`DriverRegistry.achieved`), which is why they are cumulative for the process's lifetime and
  * never reset: a per-interval count would be lost whole every time a report failed to arrive,
  * while a cumulative one costs that interval's freshness and nothing else.
  *
  * Split by [[Platform]] rather than by the coordinator's `PlanScenario` so that `scenario` does
  * not depend on `coordinator` for a two-case mapping the driver already has to make when it
  * fills in the report. `loadgen_arrivals_total` is deliberately untouched by this -- it is a
  * dashboard series whose label set the boards of D9 are already written against.
  */
trait ArrivalTally:
  def record(platform: Platform): UIO[Unit]

  def cumulative: UIO[Map[Platform, Long]]

object ArrivalTally:

  /** For a loop whose arrivals nobody reports -- the scenario-engine tests, which assert on the
    * SUT's transcript rather than on a rate.
    */
  val none: ArrivalTally =
    new ArrivalTally:
      override def record(platform: Platform): UIO[Unit] = ZIO.unit
      override def cumulative: UIO[Map[Platform, Long]] = ZIO.succeed(Map.empty)

  val make: UIO[ArrivalTally] =
    Ref.make(Map.empty[Platform, Long]).map(RefArrivalTally(_))

private final class RefArrivalTally(counts: Ref[Map[Platform, Long]]) extends ArrivalTally:
  override def record(platform: Platform): UIO[Unit] =
    counts.update(current => current.updated(platform, current.getOrElse(platform, 0L) + 1L))

  override def cumulative: UIO[Map[Platform, Long]] = counts.get
