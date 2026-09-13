package versola.loadgen.store

import zio.Chunk

import java.time.Instant

import scala.collection.mutable

/** The deferred half of dev spec §7.5: bookkeeping that describes what already happened, as
  * opposed to the critical writes (refresh token, generation bump, passkey) whose absence would
  * lose a credential. Everything here may be batched, reordered against a critical write, or --
  * under back-pressure -- dropped outright, because none of it is read back to make a decision
  * during the run.
  */
enum DeferredUpdate:
  case UserSeen(userId: Long, at: Instant)
  case SessionTouched(touch: SessionTouch)
  case EventSampled(event: EventRow)

/** A flushable batch: one write per table, per flush.
  *
  * @param userTouches
  *   at most one per user id, latest `lastSeenAt` wins.
  * @param sessionTouches
  *   at most one per session id, latest `accessExpiresAt` wins.
  * @param events
  *   every event, in arrival order -- the forensic sample is a log, not a state.
  */
case class DeferredBatch(
    userTouches: Chunk[UserTouch],
    sessionTouches: Chunk[SessionTouch],
    events: Chunk[EventRow],
):
  def isEmpty: Boolean = userTouches.isEmpty && sessionTouches.isEmpty && events.isEmpty

object DeferredBatch:
  val empty: DeferredBatch = DeferredBatch(Chunk.empty, Chunk.empty, Chunk.empty)

  /** Collapses a flush window's updates into one statement's worth per table.
    *
    * The state columns are last-write-wins by construction -- `last_seen_at` and
    * `access_expires_at` each hold a current value, not a history -- so applying every
    * intermediate value would be the same result at the cost of the round trips. At the
    * configured 500-row window a heavy user acting several times inside 200 ms is the ordinary
    * case, not the exception.
    *
    * Insertion order is preserved so a flush writes rows in roughly id order across a batch,
    * which is also the order the primary-key lookups land in.
    */
  def coalesce(updates: Chunk[DeferredUpdate]): DeferredBatch =
    val users = mutable.LinkedHashMap.empty[Long, UserTouch]
    val sessions = mutable.LinkedHashMap.empty[Long, SessionTouch]
    val events = Chunk.newBuilder[EventRow]

    updates.foreach:
      case DeferredUpdate.UserSeen(userId, at) =>
        users.update(userId, UserTouch(userId, at))
      case DeferredUpdate.SessionTouched(touch) =>
        sessions.update(touch.sessionId, touch)
      case DeferredUpdate.EventSampled(event) =>
        events += event

    DeferredBatch(Chunk.fromIterable(users.values), Chunk.fromIterable(sessions.values), events.result())
