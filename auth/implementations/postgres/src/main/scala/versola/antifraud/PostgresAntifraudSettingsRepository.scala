package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.json.JsonEncoder
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

  override def replace(tuning: Option[Tuning], version: Long, at: Instant): Task[Unit] =
    xa.transactMeasured("replace-antifraud-settings"):
      timed()
      tuning match
        case Some(settings) =>
          sql"""
            INSERT INTO af_settings (id, settings, updated_by, updated_at, version) VALUES (1, $settings, 'central', $at, $version)
            ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by,
              updated_at = EXCLUDED.updated_at, version = EXCLUDED.version
          """.update.run()
        case None => sql"DELETE FROM af_settings WHERE id = 1".update.run()
      bumped()

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
