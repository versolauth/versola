package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZIO, ZLayer, durationInt}

import java.util.UUID

/** @param statementTimeout the longest one statement may take: the flusher runs a save, a load and a prune in turn,
  *                         and a statement that hangs would stop the counts of the other instances from being read
  */
class PostgresVelocityRepository(xa: TransactorZIO, statementTimeout: Duration = 10.seconds) extends VelocityRepository, BasicCodecs:
  import PgCodec.SeqCodec
  import PostgresVelocityRepository.*
  import SqlArrayCodec.{IntSqlArrayCodec, LongSqlArrayCodec, StringSqlArrayCodec}

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  private case class Row(keyType: String, key: String, minute: Long, instanceId: UUID, count: Int) derives DbCodec

  override def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit] =
    if buckets.isEmpty then ZIO.unit
    else
      xa.transactMeasured("save-antifraud-velocity"):
        timed()
        // One statement per chunk instead of a round trip per bucket. The rows of an instance are its own, so
        // instances writing at once never touch the same row.
        buckets.grouped(SaveChunk).foreach: chunk =>
          val types = chunk.map(_.keyType.toString)
          val keys = chunk.map(_.key)
          val minutes = chunk.map(_.minute)
          val counts = chunk.map(_.count)
          sql"""
            INSERT INTO af_velocity (key_type, key, minute, instance_id, count)
            SELECT t.key_type, t.key, t.minute, $instance, t.count
            FROM unnest($types::text[], $keys::text[], $minutes::bigint[], $counts::int[]) AS t(key_type, key, minute, count)
            ON CONFLICT (key_type, key, minute, instance_id) DO UPDATE SET count = EXCLUDED.count
          """.update.run()
      .unit

  override def loadOthers(instance: UUID, fromMinute: Long, toMinute: Long, maxKeys: Int): Task[List[SharedBucket]] =
    xa.transactMeasured("load-antifraud-velocity"):
      timed()
      // Keys are ranked first and their rows read after, so the limit cuts keys and never the minutes of a key.
      sql"""
        WITH ranged AS (
          SELECT key_type, key, minute, instance_id, count
          FROM af_velocity
          WHERE minute >= $fromMinute AND minute < $toMinute AND instance_id <> $instance
        ), hot AS (
          SELECT key_type, key FROM ranged GROUP BY key_type, key ORDER BY SUM(count) DESC LIMIT $maxKeys
        )
        SELECT r.key_type, r.key, r.minute, r.instance_id, r.count
        FROM ranged r JOIN hot h ON h.key_type = r.key_type AND h.key = r.key
      """.query[Row].run().toList
    .map(_.flatMap(row =>
      VelocityKey.values.find(_.toString == row.keyType).map(keyType =>
        SharedBucket(instance = row.instanceId, bucket = VelocityBucket(keyType = keyType, key = row.key, minute = row.minute, count = row.count)),
      ),
    ))

  override def prune(minute: Long, batch: Int): Task[Int] =
    xa.transactMeasured("prune-antifraud-velocity"):
      timed()
      // One instance at a time; the others have nothing to do, the rows are going anyway.
      val locked = sql"SELECT pg_try_advisory_xact_lock($PruneLock)".query[Boolean].run().head
      if !locked then 0
      else
        sql"""
          DELETE FROM af_velocity WHERE ctid IN (SELECT ctid FROM af_velocity WHERE minute < $minute LIMIT $batch)
        """.update.run()

object PostgresVelocityRepository:
  val live: ZLayer[TransactorZIO, Nothing, VelocityRepository] =
    ZLayer.fromFunction(PostgresVelocityRepository(_))

  /** Rows per statement of a save. */
  private val SaveChunk = 2000

  /** Next to the locks of `PostgresAntifraudEventRepository`. */
  private val PruneLock = 7430003L
