package versola.configuration.system

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.system.{SystemSettingsRecord, SystemSettingsRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresSystemSettingsRepository(xa: TransactorZIO) extends SystemSettingsRepository, BasicCodecs:

  given DbCodec[SystemSettingsRecord] = DbCodec.derived

  override def getAll: Task[SystemSettingsRecord] =
    xa.connectMeasured("get-system-settings"):
      sql"""SELECT password_regex, password_history_size, password_num_different FROM system_settings WHERE id = 1"""
        .query[SystemSettingsRecord].run()
        .headOption
        .getOrElse(throw new NoSuchElementException("system_settings row not found"))

  override def upsert(record: SystemSettingsRecord): Task[Unit] =
    xa.connectMeasured("upsert-system-settings"):
      sql"""
        INSERT INTO system_settings (id, password_regex, password_history_size, password_num_different)
        VALUES (1, ${record.passwordRegex}, ${record.passwordHistorySize}, ${record.passwordNumDifferent})
        ON CONFLICT (id) DO UPDATE SET
          password_regex = EXCLUDED.password_regex,
          password_history_size = EXCLUDED.password_history_size,
          password_num_different = EXCLUDED.password_num_different
      """.update.run()
    .unit

object PostgresSystemSettingsRepository:
  def live: ZLayer[TransactorZIO, Nothing, SystemSettingsRepository] =
    ZLayer.fromFunction(PostgresSystemSettingsRepository(_))
