package versola.antifraud

import zio.Task

import java.time.LocalDate

trait AntifraudEventRepository:
  /** Writes `events` in one round trip. */
  def insertBatch(events: List[AntifraudEvent]): Task[Unit]

  /** Makes sure there is a partition for each of the `days` days starting at `from` (UTC), creating the ones
    * missing, and drops the partitions that end on or before `keepFrom`. Safe to run from several instances at
    * once. */
  def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Unit]
