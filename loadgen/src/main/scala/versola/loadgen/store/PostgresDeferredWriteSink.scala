package versola.loadgen.store

import zio.{Task, ZLayer}

/** Applies a coalesced flush window as three batched statements, one per table.
  *
  * Not one transaction: the three carry unrelated bookkeeping, and wrapping them together would
  * make a failure on the events table -- the least important of the three -- discard the
  * session state as well.
  */
class PostgresDeferredWriteSink(
    users: VirtualUserRepository,
    sessions: DeviceSessionRepository,
    events: EventRepository,
) extends DeferredWriteSink:

  override def write(batch: DeferredBatch): Task[Unit] =
    users.touchAll(batch.userTouches) *>
      sessions.touchAll(batch.sessionTouches) *>
      events.appendAll(batch.events)

object PostgresDeferredWriteSink:
  def live: ZLayer[VirtualUserRepository & DeviceSessionRepository & EventRepository, Nothing, DeferredWriteSink] =
    ZLayer.fromFunction(PostgresDeferredWriteSink(_, _, _))
