package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.audit.AuditSupport.*
import versola.util.audit.AuditEvent
import versola.util.postgres.{AuditInsert, BasicCodecs}
import zio.json.ast.Json
import zio.{Duration, Task, ZLayer, durationInt}

import java.time.Instant

class PostgresAntifraudCounterResetRepository(xa: TransactorZIO, statementTimeout: Duration = 10.seconds)
  extends AntifraudCounterResetRepository,
    BasicCodecs:
  import PostgresAntifraudCounterResetRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  override def record(reset: CounterReset.Resolved, requestedBy: String, at: Instant, audit: AuditEvent): Task[Long] =
    xa.transactMeasured("record-antifraud-counter-reset"):
      timed()
      // Generations have no gap and are in commit order only if they are made one at a time: a sequence would give them
      // out before the commit, and an instance that read generation 7 could miss a 6 that commits after it.
      sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock($ResetLock)) locked".query[Int].run()
      val generation = sql"""
        INSERT INTO af_counter_reset (generation, scope, value_type, value_hash, dest_prefix, requested_by, at)
        VALUES ((SELECT COALESCE(MAX(generation), 0) + 1 FROM af_counter_reset), ${reset.scope.toString},
                ${reset.valueType.map(_.toString)}, ${reset.valueHash}, ${reset.destPrefix}, $requestedBy, $at)
        RETURNING generation
      """.query[Long].run().head
      AuditInsert.insert(audit.withDetail("generation", Json.Num(generation)))
      generation

  override def latest: Task[Long] =
    xa.transactMeasured("antifraud-counter-reset-latest"):
      timed()
      sql"SELECT COALESCE(MAX(generation), 0) FROM af_counter_reset".query[Long].run().head

  override def since(generation: Long, limit: Int): Task[List[CounterReset]] =
    xa.transactMeasured("list-antifraud-counter-resets"):
      timed()
      sql"""
        SELECT generation, scope, value_type, value_hash, dest_prefix, requested_by, at
        FROM af_counter_reset WHERE generation > $generation ORDER BY generation LIMIT $limit
      """.query[Row].run().iterator.flatMap(_.toReset).toList

  private case class Row(
      generation: Long,
      scope: String,
      valueType: Option[String],
      valueHash: Option[String],
      destPrefix: Option[String],
      requestedBy: String,
      at: Instant,
  ) derives DbCodec:
    /** A row of a kind this version does not know is skipped, not fatal. */
    def toReset: Option[CounterReset] =
      CounterScope.values.find(_.toString == scope).map: s =>
        CounterReset(
          generation = generation,
          scope = s,
          valueType = valueType.flatMap(v => CounterValueType.values.find(_.toString == v)),
          valueHash = valueHash,
          destPrefix = destPrefix,
          requestedBy = requestedBy,
          at = at,
        )

object PostgresAntifraudCounterResetRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudCounterResetRepository] =
    ZLayer.fromFunction(PostgresAntifraudCounterResetRepository(_))

  /** Next to the locks of `PostgresAntifraudEventRepository` and `PostgresVelocityRepository`. */
  private val ResetLock = 7430004L
