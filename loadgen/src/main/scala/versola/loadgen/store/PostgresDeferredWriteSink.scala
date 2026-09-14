package versola.loadgen.store

import zio.{Chunk, Task, UIO, ZIO, ZLayer}

/** Applies a coalesced flush window as three batched statements, one per table.
  *
  * Not one transaction: the three carry unrelated bookkeeping, and wrapping them together would
  * make a failure on the events table -- the least important of the three -- discard the
  * session state as well.
  *
  * For the same reason one failing statement does not abandon the other two, and the failure
  * names the sections that did not take: a batch is only as lost as the statements that failed,
  * and [[WriteBehindBuffer]] counts the dropped updates off that. The first cause is the one
  * reported -- three connection failures in a row say the same thing once.
  */
class PostgresDeferredWriteSink(
    users: VirtualUserRepository,
    sessions: DeviceSessionRepository,
    events: EventRepository,
) extends DeferredWriteSink:

  override def write(batch: DeferredBatch): Task[Unit] =
    for
      userFailure <- attempt(DeferredSection.Users, users.touchAll(batch.userTouches))
      sessionFailure <- attempt(DeferredSection.Sessions, sessions.touchAll(batch.sessionTouches))
      eventFailure <- attempt(DeferredSection.Events, events.appendAll(batch.events))
      failures = Chunk(userFailure, sessionFailure, eventFailure).flatten
      _ <- ZIO.when(failures.nonEmpty):
        ZIO.fail(DeferredWriteFailed(failures.map(_._1).toSet, failures.head._2))
    yield ()

  private def attempt(section: DeferredSection, write: Task[Unit]): UIO[Option[(DeferredSection, Throwable)]] =
    write.fold(error => Some(section -> error), _ => None)

object PostgresDeferredWriteSink:
  def live: ZLayer[VirtualUserRepository & DeviceSessionRepository & EventRepository, Nothing, DeferredWriteSink] =
    ZLayer.fromFunction(PostgresDeferredWriteSink(_, _, _))
