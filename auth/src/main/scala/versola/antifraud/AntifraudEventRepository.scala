package versola.antifraud

import zio.Task

import java.time.{Instant, LocalDate}

trait AntifraudEventRepository:
  /** Writes `events` in one round trip. */
  def insertBatch(events: List[AntifraudEvent]): Task[Unit]

  /** Makes sure there is a partition for each of the `days` days starting at `from` (UTC), creating the ones
    * missing, and drops the partitions that end on or before `keepFrom`. Safe to run from several instances at
    * once. */
  def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Unit]

  /** Deletes the rows of events before `before` from the day's partition that `before` falls in. A partition is only dropped
    * once it lies wholly before the retention limit, so this is what makes the log keep exactly `retention`, not up to a day
    * more; the BRIN index on `at` makes it a read of the rows to delete and nothing else. Older partitions are
    * [[maintainPartitions]]'s. */
  def pruneEvents(before: Instant): Task[Unit]

  /** Recomputes `af_dest_stats` for the whole minutes in `[from, to)` from the event log, keeping the first
    * `prefixLength` digits of each number. Idempotent, so a late event is picked up by the next run over the same
    * minutes. Only one instance at a time does the work; the others return `false` without doing it. */
  def rollupStats(from: Instant, to: Instant, prefixLength: Int): Task[Boolean]

  /** Deletes the statistics of minutes before `before`. */
  def pruneStats(before: Instant): Task[Unit]
