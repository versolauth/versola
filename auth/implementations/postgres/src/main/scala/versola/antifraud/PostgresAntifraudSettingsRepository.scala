package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.audit.AuditSupport.*
import versola.util.audit.AuditEvent
import versola.util.postgres.{AuditInsert, BasicCodecs}
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.{Duration, Task, ZLayer, durationInt}

import java.time.Instant

/** @param statementTimeout the longest one statement may take, on every method: the tuning store reads the row from a fiber
  *                         of its own, and a statement that hangs (a lock, a slow disk) would keep that fiber from ever
  *                         failing, so `antifraud_tuning_refresh_failed_total` would never move
  */
class PostgresAntifraudSettingsRepository(xa: TransactorZIO, statementTimeout: Duration = 10.seconds)
  extends AntifraudSettingsRepository,
    BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[Tuning] = jsonBCodec[Tuning](using Tuning.storedDecoder, JsonEncoder[Tuning])

  /** `SET LOCAL` lasts for the transaction, so a read is made in one as well. */
  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  private def bumped()(using DbCon): Unit =
    sql"""
      INSERT INTO af_settings_version (id, version) VALUES (1, 1)
      ON CONFLICT (id) DO UPDATE SET version = af_settings_version.version + 1
    """.update.run()
    ()

  override def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long], audit: AuditEvent): Task[Option[Long]] =
    xa.transactMeasured("put-antifraud-settings"):
      timed()
      val written = expectedVersion match
        // Whatever is there is replaced (the first change creates the row).
        case None =>
          sql"""
            INSERT INTO af_settings (id, settings, updated_by, updated_at, version) VALUES (1, $tuning, $actor, $at, 1)
            ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by,
              updated_at = EXCLUDED.updated_at, version = af_settings.version + 1
            RETURNING version
          """.query[Long].run().headOption
        // The row was not there when the caller read it, and still must not be.
        case Some(0L) =>
          sql"""
            INSERT INTO af_settings (id, settings, updated_by, updated_at, version) VALUES (1, $tuning, $actor, $at, 1)
            ON CONFLICT (id) DO NOTHING
            RETURNING version
          """.query[Long].run().headOption
        case Some(expected) =>
          sql"""
            UPDATE af_settings SET settings = $tuning, updated_by = $actor, updated_at = $at, version = version + 1
            WHERE id = 1 AND version = $expected
            RETURNING version
          """.query[Long].run().headOption
      written.foreach: version =>
        bumped()
        AuditInsert.insert(audit.withDetail("version", Json.Num(version)))
      written

  override def seed(tuning: Tuning, actor: String, at: Instant): Task[Boolean] =
    xa.transactMeasured("seed-antifraud-settings"):
      timed()
      val inserted = sql"""
        INSERT INTO af_settings (id, settings, updated_by, updated_at, version) VALUES (1, $tuning, $actor, $at, 1)
        ON CONFLICT (id) DO NOTHING
      """.update.run()
      if inserted > 0 then bumped()
      inserted > 0

  override def find: Task[Option[StoredTuning]] =
    xa.transactMeasured("find-antifraud-settings"):
      timed()
      sql"SELECT settings, version FROM af_settings WHERE id = 1".query[(Tuning, Long)].run().headOption.map(StoredTuning.apply.tupled)

  override def version: Task[Long] =
    xa.transactMeasured("antifraud-settings-version"):
      timed()
      sql"SELECT COALESCE(MAX(version), 0) FROM af_settings_version".query[Long].run().head

object PostgresAntifraudSettingsRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudSettingsRepository] =
    ZLayer.fromFunction(PostgresAntifraudSettingsRepository(_))
