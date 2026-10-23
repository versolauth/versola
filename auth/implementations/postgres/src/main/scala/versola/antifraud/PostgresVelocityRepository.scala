package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Task, ZIO, ZLayer}

import java.time.{Instant, ZoneOffset}
import java.util.UUID

class PostgresVelocityRepository(xa: TransactorZIO) extends VelocityRepository, BasicCodecs:
  import PostgresVelocityRepository.*

  private case class Row(keyType: String, key: String, minute: Long, instanceId: UUID, count: Int) derives DbCodec

  override def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit] =
    if buckets.isEmpty then ZIO.unit
    else
      xa.transactMeasured("save-antifraud-velocity"):
        batchUpdate(buckets): bucket =>
          sql"""
            INSERT INTO af_velocity (key_type, key, minute, instance_id, count, updated_at)
            VALUES (${bucket.keyType.toString}, ${bucket.key}, ${bucket.minute}, $instance, ${bucket.count}, now())
            ON CONFLICT (key_type, key, minute, instance_id) DO UPDATE SET count = EXCLUDED.count, updated_at = now()
          """.update
      .unit

  override def loadOthers(instance: UUID, fromMinute: Long, since: Instant, maxKeys: Int): Task[List[SharedBucket]] =
    val sinceAt = since.atOffset(ZoneOffset.UTC)
    xa.connectMeasured("load-antifraud-velocity"):
      // Keys are ranked first and their rows read after, so the limit cuts keys and never the minutes of a key.
      sql"""
        WITH changed AS (
          SELECT key_type, key, minute, instance_id, count
          FROM af_velocity
          WHERE minute >= $fromMinute AND updated_at >= $sinceAt AND instance_id <> $instance
        ), hot AS (
          SELECT key_type, key FROM changed GROUP BY key_type, key ORDER BY SUM(count) DESC LIMIT $maxKeys
        )
        SELECT c.key_type, c.key, c.minute, c.instance_id, c.count
        FROM changed c JOIN hot h ON h.key_type = c.key_type AND h.key = c.key
      """.query[Row].run().toList
    .map(_.flatMap(row =>
      VelocityKey.values.find(_.toString == row.keyType).map(keyType =>
        SharedBucket(instance = row.instanceId, bucket = VelocityBucket(keyType = keyType, key = row.key, minute = row.minute, count = row.count)),
      ),
    ))

  override def prune(minute: Long): Task[Unit] =
    xa.transactMeasured("prune-antifraud-velocity"):
      // One instance at a time; the others have nothing to do, the rows are going anyway.
      val locked = sql"SELECT pg_try_advisory_xact_lock($PruneLock)".query[Boolean].run().head
      if locked then
        sql"DELETE FROM af_velocity WHERE minute < $minute".update.run()
        ()
    .unit

object PostgresVelocityRepository:
  val live: ZLayer[TransactorZIO, Nothing, VelocityRepository] =
    ZLayer.fromFunction(PostgresVelocityRepository(_))

  /** Next to the locks of `PostgresAntifraudEventRepository`. */
  private val PruneLock = 7430003L
