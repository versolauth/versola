package versola.util.audit

import versola.util.partitions.{PartitionReport, TableOwnership}
import zio.Task

import java.time.{Instant, LocalDate}

/** The storage of a service's audit log: a day-partitioned, append-only table whose partitions the service maintains. */
trait AuditEventRepository:
  /** Writes `events` in one round trip. */
  def insertBatch(events: List[AuditEvent]): Task[Unit]

  /** Makes sure there is a partition for each of the `days` days starting at `from` (UTC), creating the ones missing, and drops
    * the partitions that end on or before `keepFrom`. A day whose rows sit in the default partition is not attached (the rows are
    * never moved: an insert-only replication of the table would see them twice) and is reported. Safe to run from several
    * instances at once. */
  def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport]

  /** How many days after `today` (UTC) have a partition: what the writer has left before its rows go to the default partition. */
  def partitionDaysAhead(today: LocalDate): Task[Int]

  /** Deletes the rows older than `before` from the default partition, in batches; how many it deleted. The partitions of old days
    * are dropped by [[maintainPartitions]], but the default partition is never dropped, so its rows are this method's alone. */
  def pruneDefault(before: Instant): Task[Long]

  /** Who owns the table and who the service is: partitions can be created and dropped only by the owner (or a member of the owning
    * role), and a deployment that migrated as another role finds that out on the first run. */
  def ownership: Task[TableOwnership]
