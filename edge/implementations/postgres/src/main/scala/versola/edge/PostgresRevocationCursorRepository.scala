package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.revocation.RevocationCursorRepository
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer}

class PostgresRevocationCursorRepository(xa: TransactorZIO) extends RevocationCursorRepository, BasicCodecs:

  override def seed(source: String): Task[Unit] =
    xa.connectMeasured("seed-revocation-cursor"):
      sql"""
        INSERT INTO revocation_cursors (source, cursor, lease_until, leased_by, updated_at, last_read_at)
        VALUES ($source, '', to_timestamp(0), NULL, NOW(), NOW())
        ON CONFLICT (source) DO NOTHING
      """.update.run()
    .unit

  /** `lease_until < NOW()` on the database's clock, in the statement that takes the row: the check and the take
    * cannot interleave with another pod's, and no transaction outlives the statement.
    */
  override def acquire(source: String, holder: String, lease: Duration): Task[Option[String]] =
    val seconds = seconds0(lease)
    xa.connectMeasured("acquire-revocation-cursor"):
      sql"""
        UPDATE revocation_cursors
        SET lease_until = NOW() + make_interval(secs => $seconds), leased_by = $holder
        WHERE source = $source AND lease_until < NOW()
        RETURNING cursor
      """.query[String].run().headOption

  override def renew(source: String, holder: String, lease: Duration): Task[Boolean] =
    val seconds = seconds0(lease)
    xa.connectMeasured("renew-revocation-cursor"):
      sql"""
        UPDATE revocation_cursors
        SET lease_until = NOW() + make_interval(secs => $seconds)
        WHERE source = $source AND leased_by = $holder AND lease_until >= NOW()
      """.update.run() == 1

  override def advance(source: String, holder: String, cursor: String, lease: Duration): Task[Boolean] =
    val seconds = seconds0(lease)
    xa.connectMeasured("advance-revocation-cursor"):
      sql"""
        UPDATE revocation_cursors
        SET cursor = $cursor, updated_at = NOW(), lease_until = NOW() + make_interval(secs => $seconds)
        WHERE source = $source AND leased_by = $holder AND lease_until >= NOW()
      """.update.run() == 1

  /** Back to a past instant rather than a NULL, which the column does not allow. */
  override def release(source: String, holder: String): Task[Unit] =
    xa.connectMeasured("release-revocation-cursor"):
      sql"""
        UPDATE revocation_cursors SET lease_until = to_timestamp(0)
        WHERE source = $source AND leased_by = $holder
      """.update.run()
    .unit

  override def completed(source: String, holder: String): Task[Unit] =
    xa.connectMeasured("complete-revocation-cursor"):
      sql"""
        UPDATE revocation_cursors SET lease_until = to_timestamp(0), last_read_at = NOW()
        WHERE source = $source AND leased_by = $holder
      """.update.run()
    .unit

  override def lagSeconds(source: String): Task[Option[Long]] =
    xa.connectMeasured("revocation-feed-lag"):
      sql"""
        SELECT floor(extract(epoch FROM (NOW() - last_read_at)))::bigint FROM revocation_cursors WHERE source = $source
      """.query[Long].run().headOption

  private def seconds0(lease: Duration): Double = lease.toMillis / 1000.0

object PostgresRevocationCursorRepository:
  val live: ZLayer[TransactorZIO, Nothing, RevocationCursorRepository] =
    ZLayer.fromFunction(PostgresRevocationCursorRepository(_))
