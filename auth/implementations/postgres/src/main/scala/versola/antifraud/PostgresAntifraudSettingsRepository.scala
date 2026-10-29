package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresAntifraudSettingsRepository(xa: TransactorZIO) extends AntifraudSettingsRepository, BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[Tuning] = jsonBCodec[Tuning]

  private def bumped()(using DbCon): Unit =
    sql"""
      INSERT INTO af_settings_version (id, version) VALUES (1, 1)
      ON CONFLICT (id) DO UPDATE SET version = af_settings_version.version + 1
    """.update.run()
    ()

  override def put(tuning: Tuning, actor: String, at: Instant): Task[Unit] =
    xa.transactMeasured("put-antifraud-settings"):
      sql"""
        INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, $tuning, $actor, $at)
        ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
      """.update.run()
      bumped()

  override def seed(tuning: Tuning, actor: String, at: Instant): Task[Boolean] =
    xa.transactMeasured("seed-antifraud-settings"):
      val inserted = sql"""
        INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, $tuning, $actor, $at)
        ON CONFLICT (id) DO NOTHING
      """.update.run()
      if inserted > 0 then bumped()
      inserted > 0

  override def find: Task[Option[Tuning]] =
    xa.connectMeasured("find-antifraud-settings"):
      sql"SELECT settings FROM af_settings WHERE id = 1".query[Tuning].run().headOption

  override def version: Task[Long] =
    xa.connectMeasured("antifraud-settings-version"):
      sql"SELECT COALESCE(MAX(version), 0) FROM af_settings_version".query[Long].run().head

object PostgresAntifraudSettingsRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudSettingsRepository] =
    ZLayer.fromFunction(PostgresAntifraudSettingsRepository(_))
