package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.{Chunk, Task, ZIO, ZLayer}

class PostgresEventRepository(xa: TransactorZIO) extends EventRepository, StoreCodecs:

  override def appendAll(events: Chunk[EventRow]): Task[Unit] =
    if events.isEmpty then ZIO.unit
    else
      xa.transactMeasured("append-vu-events"):
        batchUpdate(events): event =>
          sql"""
            INSERT INTO vu_events (at, user_id, scenario, step, outcome, latency_ms)
            VALUES (${event.at}, ${event.userId}, ${event.scenario}, ${event.step},
                    ${event.outcome}, ${event.latencyMs})
          """.update
      .unit

object PostgresEventRepository:
  def live: ZLayer[TransactorZIO, Throwable, EventRepository] =
    ZLayer.fromFunction(PostgresEventRepository(_))
