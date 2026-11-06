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
      // Keys are ranked first and their rows read after, so the limit cuts keys and never the minutes of a key. The
      // ranking only aggregates (a key and a sum per key, the top `maxKeys` kept), and each hot key's rows are then read by
      // the primary key, so no row of the range is held for the second step: the range read is as large as the busiest keys.
      sql"""
        WITH hot AS (
          SELECT key_type, key FROM af_velocity
          WHERE minute >= $fromMinute AND minute < $toMinute AND instance_id <> $instance
          GROUP BY key_type, key ORDER BY SUM(count) DESC LIMIT $maxKeys
        )
        SELECT r.key_type, r.key, r.minute, r.instance_id, r.count
        FROM hot h
        JOIN af_velocity r ON r.key_type = h.key_type AND r.key = h.key
          AND r.minute >= $fromMinute AND r.minute < $toMinute AND r.instance_id <> $instance
      """.query[Row].run().toList
    .map(_.flatMap(row =>
      VelocityKey.values.find(_.toString == row.keyType).map(keyType =>
        SharedBucket(instance = row.instanceId, bucket = VelocityBucket(keyType = keyType, key = row.key, minute = row.minute, count = row.count)),
      ),
    ))

  override def forget(target: VelocityTarget, instance: Option[UUID]): Task[Int] =
    xa.transactMeasured("forget-antifraud-velocity"):
      timed()
      val own = instance.isEmpty
      val who = instance.getOrElse(NoInstance)
      target match
        case VelocityTarget.All =>
          sql"DELETE FROM af_velocity WHERE $own OR instance_id = $who".update.run()
        case VelocityTarget.Key(keyType, key) =>
          val kind = keyType.toString
          sql"DELETE FROM af_velocity WHERE key_type = $kind AND key = $key AND ($own OR instance_id = $who)".update.run()
        case VelocityTarget.Range(digits) =>
          // `tenant/digits`: what follows the last slash.
          val like = s"$digits%"
          sql"""
            DELETE FROM af_velocity
            WHERE key_type = 'prefix' AND substring(key from '[^/]*$$') LIKE $like AND ($own OR instance_id = $who)
          """.update.run()

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

  private val NoInstance = new UUID(0L, 0L)

  /** Rows per statement of a save. */
  private val SaveChunk = 2000

  /** Next to the locks of `PostgresAntifraudEventRepository`. */
  private val PruneLock = 7430003L
