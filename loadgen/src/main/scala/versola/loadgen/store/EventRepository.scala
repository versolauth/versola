package versola.loadgen.store

import zio.{Chunk, Task}

/** `vu_events` (migration V0003): the 1% forensic sample. Batch-only by construction -- there
  * is no single-row insert, because a per-step write on the hot path would make the emulator's
  * own bookkeeping a measurable part of the load it is supposed to be measuring.
  */
trait EventRepository:
  def appendAll(events: Chunk[EventRow]): Task[Unit]
