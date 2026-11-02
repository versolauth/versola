package versola.antifraud

import zio.{Duration, Task}

import java.time.{Instant, LocalDate}

trait AntifraudEventRepository:
  /** Writes `events` in one round trip. */
  def insertBatch(events: List[AntifraudEvent]): Task[Unit]

  /** Makes sure there is a partition for each of the `days` days starting at `from` (UTC), creating the ones
    * missing, and drops the partitions that end on or before `keepFrom`. The creates are one transaction and the drops
    * another, so a drop that fails never takes the partitions made ahead with it; both are tried, and the failure of the
    * first that failed is the result. Safe to run from several instances at once. */
  def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Unit]

  /** How many days after `today` (UTC) have a partition: what the writer has left before it finds none. */
  def partitionDaysAhead(today: LocalDate): Task[Int]

  /** Recomputes `af_dest_stats` for the whole minutes in `[from, to)` from the event log, keeping the first
    * `prefixLength` digits of each number. Idempotent, so a late event is picked up by the next run over the same
    * minutes. Only one instance at a time does the work, and one that runs within half an `interval` of the last run (by any
    * instance) does not; the others return `false` without doing it. A group that has not changed is not rewritten. */
  def rollupStats(from: Instant, to: Instant, prefixLength: Int, interval: Duration): Task[Boolean]

  /** Deletes the statistics of minutes before `before`. */
  def pruneStats(before: Instant): Task[Unit]
