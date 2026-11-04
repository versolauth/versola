package versola.antifraud

import versola.util.partitions.TableOwnership
import zio.{Duration, Task}

import java.time.{Instant, LocalDate}

trait AntifraudEventRepository:
  /** Writes `events` in one round trip. */
  def insertBatch(events: List[AntifraudEvent]): Task[Unit]

  /** Makes sure there is a partition for each of the `days` days starting at `from` (UTC), creating the ones
    * missing, and drops the partitions that end on or before `keepFrom`. The creates are one phase and the drops
    * another, so a drop that fails never takes the partitions made ahead with it; both are tried, and the failure of the
    * first that failed is the result. Safe to run from several instances at once.
    *
    * A day's rows that were written while it had no partition went to the default partition; they are moved into the
    * day's partition as it is created, and their number is the result (the caller reports it: a day ran without a partition). */
  def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Long]

  /** Who owns the event log's table and who the service is: partitions can be created and dropped only by the owner (or a member of
    * the owning role), and a deployment that migrated as another role finds that out on the first run. */
  def ownership: Task[TableOwnership]

  /** How many days after `today` (UTC) have a partition: what the writer has left before it finds none. */
  def partitionDaysAhead(today: LocalDate): Task[Int]

  /** Deletes the rows of events before `before` from the day's partition that `before` falls in, and from the default partition
    * whatever day they are of. A partition is only dropped once it lies wholly before the retention limit, so this is what makes
    * the log keep exactly `retention`, not up to a day more; the BRIN index on `at` makes it a read of the rows to delete and
    * nothing else. Older partitions are [[maintainPartitions]]'s, but the default partition is never dropped, so its rows are this
    * method's alone. */
  def pruneEvents(before: Instant): Task[Unit]

  /** Recomputes `af_dest_stats` for the whole minutes in `[from, to)` from the event log, keeping the first
    * `prefixLength` digits of each number. Idempotent, so a late event is picked up by the next run over the same
    * minutes. Only one instance at a time does the work, and one that runs within half an `interval` of the last run (by any
    * instance) does not; the others return `false` without doing it. A group that has not changed is not rewritten. */
  def rollupStats(from: Instant, to: Instant, prefixLength: Int, interval: Duration): Task[Boolean]

  /** Deletes the statistics of minutes before `before`. */
  def pruneStats(before: Instant): Task[Unit]
