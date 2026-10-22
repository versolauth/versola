package versola.antifraud

import zio.Task

import java.util.UUID

/** Where the instances share their velocity buckets (`af_velocity`). */
trait VelocityRepository:
  /** Writes `buckets` as the totals of `instance` for those minutes, replacing what it wrote for them before, so a
    * write that is repeated (after a failure of the answer, say) changes nothing. */
  def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit]

  /** What every instance but `instance` has written for the minutes from `fromMinute` on, summed per key and minute;
    * at most `limit` of them, the busiest first, so that a table crowded with junk keys costs the keys that matter
    * nothing. */
  def loadOthers(instance: UUID, fromMinute: Long, limit: Int): Task[List[VelocityBucket]]

  /** Forgets what was written for minutes before `minute`. */
  def prune(minute: Long): Task[Unit]
