package versola.util.partitions

import java.time.LocalDate

/** What one run of a table's day-partition maintenance did. Here, and not with the Postgres code that produces it, so that a
  * repository trait of a service module can return it.
  *
  * @param movedFromDefault   rows a day's partition took over from the default partition as it was attached (a day ran without a
  *                           partition); always 0 for a table whose rows are never moved
  * @param daysLeftInDefault  days that were not attached because the default partition holds rows of theirs and the table's rows
  *                           are never moved: those rows stay in the default until they are pruned, and the service alerts
  * @param created            partitions attached by this run
  * @param dropped            partitions (and loose day tables) dropped by this run
  */
case class PartitionReport(movedFromDefault: Long, daysLeftInDefault: List[LocalDate], created: Int, dropped: Int)

/** The owner of a partitioned table (a role name), the role the service connects as, and whether that role may alter the table:
  * it owns it, is a member of the owner, or is a superuser. Partitions can be created and dropped only then, and a deployment
  * that migrated as another role finds that out on the first run. */
case class TableOwnership(owner: String, role: String, mayAlter: Boolean)
