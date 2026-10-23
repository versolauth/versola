package versola.antifraud

import zio.Task

import java.time.Instant
import java.util.UUID

/** Where the instances share their velocity buckets (`af_velocity`). */
trait VelocityRepository:
  /** Writes `buckets` as the totals of `instance` for those minutes, replacing what it wrote for them before, so a
    * write that is repeated (after a failure of the answer, say) changes nothing. */
  def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit]

  /** What every instance but `instance` wrote for the minutes from `fromMinute` on and changed at or after `since`
    * (everything, for `Instant.EPOCH`), per instance, key and minute. At most `maxKeys` keys: the busiest, ranked by
    * their count over those rows, and all their rows, so that a hot key is never cut short of its older minutes
    * by a flood of other keys. */
  def loadOthers(instance: UUID, fromMinute: Long, since: Instant, maxKeys: Int): Task[List[SharedBucket]]

  /** Forgets what was written for minutes before `minute`. Every instance asks; only one at a time does it, and the
    * others return at once. */
  def prune(minute: Long): Task[Unit]
