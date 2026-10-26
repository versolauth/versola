package versola.configuration.delivery

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.central.configuration.delivery.{DeliverySettingsRecord, DeliverySettingsRepository, OtpProviderDeliverySettings, SmtpDeliverySettings}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresDeliverySettingsRepository(xa: TransactorZIO) extends DeliverySettingsRepository, BasicCodecs:

  given JsonBDbCodec[SmtpDeliverySettings] = dbCodecFromJsonCodec[SmtpDeliverySettings]
  given JsonBDbCodec[OtpProviderDeliverySettings] = dbCodecFromJsonCodec[OtpProviderDeliverySettings]
  given DbCodec[DeliverySettingsRecord] = DbCodec.derived

  override def getAll: Task[Option[DeliverySettingsRecord]] =
    xa.connectMeasured("get-delivery-settings"):
      sql"""SELECT smtp, smtp_password, otp_provider, otp_provider_password FROM delivery_settings WHERE id = 1"""
        .query[DeliverySettingsRecord].run()
        .headOption

  override def upsert(record: DeliverySettingsRecord): Task[Unit] =
    xa.connectMeasured("upsert-delivery-settings"):
      sql"""
        INSERT INTO delivery_settings (id, smtp, smtp_password, otp_provider, otp_provider_password)
        VALUES (1, ${record.smtp}, ${record.smtpPassword}, ${record.otpProvider}, ${record.otpProviderPassword})
        ON CONFLICT (id) DO UPDATE SET
          smtp = EXCLUDED.smtp,
          smtp_password = EXCLUDED.smtp_password,
          otp_provider = EXCLUDED.otp_provider,
          otp_provider_password = EXCLUDED.otp_provider_password
      """.update.run()
    .unit

object PostgresDeliverySettingsRepository:
  def live: ZLayer[TransactorZIO, Nothing, DeliverySettingsRepository] =
    ZLayer.fromFunction(PostgresDeliverySettingsRepository(_))
