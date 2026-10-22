package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Task, ZIO, ZLayer}

import java.util.UUID

class PostgresVelocityRepository(xa: TransactorZIO) extends VelocityRepository, BasicCodecs:

  private case class Row(keyType: String, key: String, minute: Long, count: Long) derives DbCodec

  override def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit] =
    if buckets.isEmpty then ZIO.unit
    else
      xa.transactMeasured("save-antifraud-velocity"):
        batchUpdate(buckets): bucket =>
          sql"""
            INSERT INTO af_velocity (key_type, key, minute, instance_id, count)
            VALUES (${bucket.keyType.toString}, ${bucket.key}, ${bucket.minute}, $instance, ${bucket.count})
            ON CONFLICT (key_type, key, minute, instance_id) DO UPDATE SET count = EXCLUDED.count
          """.update
      .unit

  override def loadOthers(instance: UUID, fromMinute: Long, limit: Int): Task[List[VelocityBucket]] =
    xa.connectMeasured("load-antifraud-velocity"):
      sql"""
        SELECT key_type, key, minute, SUM(count) AS count
        FROM af_velocity
        WHERE minute >= $fromMinute AND instance_id <> $instance
        GROUP BY key_type, key, minute
        ORDER BY SUM(count) DESC
        LIMIT $limit
      """.query[Row].run().toList
    .map(_.flatMap(row =>
      VelocityKey.values.find(_.toString == row.keyType).map(keyType =>
        VelocityBucket(keyType = keyType, key = row.key, minute = row.minute, count = math.min(row.count, Int.MaxValue.toLong).toInt),
      ),
    ))

  override def prune(minute: Long): Task[Unit] =
    xa.connectMeasured("prune-antifraud-velocity"):
      sql"DELETE FROM af_velocity WHERE minute < $minute".update.run()
    .unit

object PostgresVelocityRepository:
  val live: ZLayer[TransactorZIO, Nothing, VelocityRepository] =
    ZLayer.fromFunction(PostgresVelocityRepository(_))
