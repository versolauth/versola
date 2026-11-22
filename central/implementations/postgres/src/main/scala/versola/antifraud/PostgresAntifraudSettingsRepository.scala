package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.antifraud.{AntifraudSettings, AntifraudSettingsRepository, VersionedAntifraudSettings}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresAntifraudSettingsRepository(xa: TransactorZIO) extends AntifraudSettingsRepository, BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[AntifraudSettings] = jsonBCodec[AntifraudSettings]

  override def find: Task[Option[VersionedAntifraudSettings]] =
    xa.connectMeasured("find-antifraud-settings"):
      sql"SELECT settings, version FROM antifraud_settings WHERE id = 1"
        .query[(AntifraudSettings, Long)].run().headOption.map(VersionedAntifraudSettings.apply.tupled)

  override def put(settings: AntifraudSettings, actor: String, at: Instant, expectedVersion: Option[Long]): Task[Option[Long]] =
    xa.connectMeasured("put-antifraud-settings"):
      expectedVersion match
        // Whatever is there is replaced (the first change creates the row).
        case None =>
          sql"""
            INSERT INTO antifraud_settings (id, settings, version, updated_by, updated_at) VALUES (1, $settings, 1, $actor, $at)
            ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by,
              updated_at = EXCLUDED.updated_at, version = antifraud_settings.version + 1
            RETURNING version
          """.query[Long].run().headOption
        // The row was not there when the caller read it, and still must not be.
        case Some(0L) =>
          sql"""
            INSERT INTO antifraud_settings (id, settings, version, updated_by, updated_at) VALUES (1, $settings, 1, $actor, $at)
            ON CONFLICT (id) DO NOTHING
            RETURNING version
          """.query[Long].run().headOption
        case Some(expected) =>
          sql"""
            UPDATE antifraud_settings SET settings = $settings, updated_by = $actor, updated_at = $at, version = version + 1
            WHERE id = 1 AND version = $expected
            RETURNING version
          """.query[Long].run().headOption

object PostgresAntifraudSettingsRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudSettingsRepository] =
    ZLayer.fromFunction(PostgresAntifraudSettingsRepository(_))
