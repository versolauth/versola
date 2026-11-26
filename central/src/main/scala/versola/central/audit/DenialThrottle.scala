package versola.central.audit

import zio.{Clock, Duration, Ref, UIO, durationInt}

import java.time.Instant

/** At most one event per key and `interval`, for the refusals a caller nobody has authenticated can cause as often as it likes (a
  * token that is unknown, spent or another's): the audit log says that someone tried and does not take a row for every try.
  *
  * Only a key the caller cannot invent is worth using: the id of a registered edge or client. What is kept is the time of the last
  * event of each key within the interval, dropped as it expires, so it never holds more keys than there are registered ones. */
final class DenialThrottle(interval: Duration, last: Ref[Map[String, Instant]]):

  /** Whether an event for `key` is due now, which also starts its interval. */
  def admit(key: String): UIO[Boolean] =
    Clock.instant.flatMap: now =>
      last.modify: known =>
        val live = known.filter((_, at) => at.plus(interval).isAfter(now))
        (!live.contains(key), live.updated(key, if live.contains(key) then live(key) else now))

  /** The keys it holds, for a spec of what it is bounded by. */
  def tracked: UIO[Set[String]] = last.get.map(_.keySet)

object DenialThrottle:
  /** One event per key per minute. */
  val Interval: Duration = 1.minute

  def make(interval: Duration): UIO[DenialThrottle] = Ref.make(Map.empty[String, Instant]).map(DenialThrottle(interval, _))

  def perMinute: UIO[DenialThrottle] = make(Interval)
