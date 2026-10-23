package versola.antifraud

import zio.Task

import java.util.UUID

/** Where the instances share their velocity buckets (`af_velocity`). */
trait VelocityRepository:
  /** Writes `buckets` as the totals of `instance` for those minutes, replacing what it wrote for them before, so a
    * write that is repeated (after a failure of the answer, say) changes nothing. */
  def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit]

  /** What every instance but `instance` wrote for the minutes `fromMinute` up to (not including) `toMinute`, per
    * instance, key and minute. At most `maxKeys` keys: the busiest, ranked by their count over the range, and all
    * their rows, so that a hot key is never cut short of its minutes by a flood of other keys. A range, not the whole
    * day, so that the query reads an index range whose size does not grow with the table. */
  def loadOthers(instance: UUID, fromMinute: Long, toMinute: Long, maxKeys: Int): Task[List[SharedBucket]]

  /** Forgets at most `batch` rows of minutes before `minute`, and says how many it did; 0 for an instance that does not
    * get the lock, because another is at it. A caller repeats while it gets `batch`. */
  def prune(minute: Long, batch: Int): Task[Int]
