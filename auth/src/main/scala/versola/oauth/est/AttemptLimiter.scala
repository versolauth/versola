package versola.oauth.est

import zio.*

import java.time.Instant

/** At most `limit` attempts per key in each `window`, counted in memory (per replica) with a fixed window. For what an
  * unauthenticated caller can make auth ask central: `simpleenroll` is keyed by the client id the caller names (or one
  * shared key where it names none -- a Bearer token). The table is bounded: past `MaxKeys` live keys every newcomer shares
  * one key, so a caller that varies the key cannot grow it, and cannot dodge the limit either. */
final class AttemptLimiter(limit: Int, window: Duration, state: Ref[Map[String, (Instant, Int)]]):

  /** Counts an attempt; false when the key has used up its window. */
  def admit(key: String): UIO[Boolean] =
    Clock.instant.flatMap: now =>
      state.modify: entries =>
        val live = if entries.size >= AttemptLimiter.MaxKeys then entries.filter((_, entry) => entry._1.plus(window).isAfter(now)) else entries
        val bucket = if live.contains(key) || live.size < AttemptLimiter.MaxKeys then key else AttemptLimiter.Overflow
        live.get(bucket) match
          case Some((started, count)) if started.plus(window).isAfter(now) =>
            (count < limit, live.updated(bucket, (started, count + 1)))
          case _ => (true, live.updated(bucket, (now, 1)))

object AttemptLimiter:
  val MaxKeys = 10_000
  private val Overflow = "\u0000overflow"

  def make(limit: Int, window: Duration): UIO[AttemptLimiter] =
    Ref.make(Map.empty[String, (Instant, Int)]).map(AttemptLimiter(limit, window, _))

  /** For tests that are not about the limit. */
  val unlimited: UIO[AttemptLimiter] = make(Int.MaxValue, 1.minute)
