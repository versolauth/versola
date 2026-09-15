package versola.loadgen.scenario

import versola.loadgen.config.ShardConfig
import versola.loadgen.store.DeviceSessionRepository
import zio.{Task, ZIO}

/** What a driver does with the sessions its previous life left mid-rotation: it **retires them,
  * it does not replay them** (issue #278, dev spec §7.4).
  *
  * A row whose `generation` was bumped but whose `refresh_generation` was not is one whose stored
  * refresh token is the predecessor of an exchange nobody knows the outcome of -- the SUT may
  * have rotated it, or may never have seen it, and the row cannot tell the two apart. Presenting
  * it is indistinguishable from reuse, so the SUT would answer `RefreshAlreadyExchanged` and the
  * campaign would report a `refresh_rejected` that is entirely the emulator's own doing. §7.4
  * fixes that metric at ~0 precisely so that a non-zero value means something; a recovery path
  * that replayed would make it mean nothing.
  *
  * Deleting the row costs the user one full login on its next arrival, which is one login per
  * session that was mid-rotation when a driver died -- bounded by the driver's concurrency, not
  * by its population.
  *
  * This runs before the driver schedules anything. It is not a background sweep: a session left
  * behind is only dangerous while something might still pick it up, and [[listLive]] already
  * excludes these rows, so the window this closes is the one between startup and the first
  * arrival on a user that owns one.
  */
object SessionRecovery:

  /** How many rows one pass claims. Bounded so the query is a page of the index rather than
    * whatever a long outage accumulated, and repeated until it comes back short -- a driver that
    * crashed repeatedly can have more of these than one page holds, and stopping at the first
    * page would leave the rest to be found by nothing.
    */
  val pageSize: Int = 500

  /** @return how many sessions were retired, for the startup log and for the tests that assert
    *         the campaign began with none left over.
    */
  def retireInterruptedRotations(sessions: DeviceSessionRepository, shard: ShardConfig): Task[Int] =
    def pass(retired: Int): Task[Int] =
      sessions.listInterruptedRotations(shard.index, pageSize).flatMap: stranded =>
        if stranded.isEmpty then ZIO.succeed(retired)
        else
          ZIO.foreachDiscard(stranded)(session => sessions.delete(session.id)) *>
            (if stranded.size < pageSize then ZIO.succeed(retired + stranded.size) else pass(retired + stranded.size))

    pass(0).tap: retired =>
      ZIO
        .logInfo(s"Retired $retired session(s) left mid-rotation by a previous driver on shard ${shard.index}")
        .when(retired > 0)
        .unit
