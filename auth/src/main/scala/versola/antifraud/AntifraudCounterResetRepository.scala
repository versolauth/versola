package versola.antifraud

import versola.util.audit.AuditEvent
import zio.Task

import java.time.Instant

/** Where a reset of the counters is written for the instances to read (`af_counter_reset`). */
trait AntifraudCounterResetRepository:
  /** Writes the reset and returns its generation, which counts from 1 with no gap, in the order of commit. The row is
    * also the audit of the reset. `audit` (`antifraud.counters_reset`) is inserted in the same transaction, which says which
    * generation it was. */
  def record(reset: CounterReset.Resolved, requestedBy: String, at: Instant, audit: AuditEvent): Task[Long]

  /** The generation of the last reset, 0 if there was none. */
  def latest: Task[Long]

  /** The resets after `generation`, oldest first, at most `limit`. */
  def since(generation: Long, limit: Int): Task[List[CounterReset]]
