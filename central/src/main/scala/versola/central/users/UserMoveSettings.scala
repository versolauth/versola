package versola.central.users

import zio.{Duration, ZLayer}

/** How moves of users between cells are driven (versolauth/versola#1051). The values are the spec's; they are not keys of the
  * configuration file, and a test sets its own.
  *
  * @param settleFor how long after the source cell was told the user is leaving it waits before the rows are read: requests already
  *   in flight for the user finish in that time. Waited by scheduling, not by sleeping
  * @param maxAttempts consecutive failures of one step before the flip after which the move is abandoned
  * @param maxRedeliveries how many times, before the flip, the rows are delivered to the target again because the source changed
  *   them since the last delivery, before the move is abandoned as `source_changed`
  * @param parallel how many moves of one draining cell are unfinished at a time
  * @param retryAfter how long after a move failed the drain of the cell tries the same user again
  * @param pollInterval how often the processor and the drain look for work
  * @param batchSize how many moves one pass of the processor takes
  * @param lease how long a move belongs to the instance that took it, before another may
  * @param maxBackoff the longest wait between two attempts of a step
  */
case class UserMoveSettings(
    settleFor: Duration = Duration.fromSeconds(2),
    maxAttempts: Int = 5,
    maxRedeliveries: Int = 3,
    parallel: Int = 4,
    retryAfter: Duration = Duration.fromSeconds(600),
    pollInterval: Duration = Duration.fromSeconds(1),
    batchSize: Int = 16,
    lease: Duration = Duration.fromSeconds(60),
    maxBackoff: Duration = Duration.fromSeconds(300),
)

object UserMoveSettings:
  val live: ZLayer[Any, Nothing, UserMoveSettings] = ZLayer.succeed(UserMoveSettings())
