package versola.configuration.locales

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.locales.{LocaleRecord, LocaleRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresLocaleRepository(xa: TransactorZIO) extends LocaleRepository, BasicCodecs:

  given DbCodec[LocaleRecord] = DbCodec.derived

  override def getAll: Task[Vector[LocaleRecord]] =
    xa.connectMeasured("get-all-locales"):
      sql"""SELECT code, name, is_default, active FROM locales ORDER BY code""".query[LocaleRecord].run()

  override def update(add: Vector[LocaleRecord], delete: Vector[String]): Task[Unit] =
    xa.repeatableRead.transactMeasured("update-locales"):
      delete.foreach { code =>
        sql"""DELETE FROM locales WHERE code = $code""".update.run()
        sql"""UPDATE forms SET localizations = localizations - $code WHERE jsonb_exists(localizations, $code)""".update.run()
        sql"""UPDATE otp_templates SET localizations = localizations - $code WHERE jsonb_exists(localizations, $code)""".update.run()
      }
      add.foreach { locale =>
        sql"""INSERT INTO locales (code, name, is_default, active) VALUES (${locale.code}, ${locale.name}, ${locale.isDefault}, ${locale.active})
              ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, active = EXCLUDED.active""".update.run()
      }

  override def setDefault(code: String): Task[Unit] =
    xa.repeatableRead.transactMeasured("set-default-locale"):
      sql"""UPDATE locales SET is_default = FALSE WHERE is_default = TRUE""".update.run()
      sql"""UPDATE locales SET is_default = TRUE WHERE code = $code""".update.run()
    .unit

object PostgresLocaleRepository:
  def live: ZLayer[TransactorZIO, Nothing, LocaleRepository] =
    ZLayer.fromFunction(PostgresLocaleRepository(_))
