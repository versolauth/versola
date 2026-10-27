package versola.configuration.delivery

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.central.configuration.delivery.{DeliverySettingsRecord, DeliverySettingsRepository, OtpProviderDeliverySettings, SmtpDeliverySettings}
import versola.util.Secret
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

  override def upsert(write: DeliverySettingsRepository.Write): Task[Unit] =
    val (keepSmtpRequested, smtpPassword) = split(write.smtpPassword)
    val (keepOtpProviderRequested, otpProviderPassword) = split(write.otpProviderPassword)
    xa.transactMeasured("upsert-delivery-settings"):
      // The row is locked while it is read, so the check below and the write that follows see one record. A kept password
      // stays only with the server it was saved for: whoever the service checked against a moment ago, a save that
      // changes the server and keeps the password writes none, instead of handing the password to the new server.
      val stored = sql"""SELECT smtp, smtp_password, otp_provider, otp_provider_password FROM delivery_settings WHERE id = 1 FOR UPDATE"""
        .query[DeliverySettingsRecord].run()
        .headOption
      val keepSmtp = keepSmtpRequested && stored.exists(record =>
        record.smtp.zip(write.smtp).exists((saved, next) => SmtpDeliverySettings.sameServer(saved, next)),
      )
      val keepOtpProvider = keepOtpProviderRequested && stored.exists(record =>
        record.otpProvider.zip(write.otpProvider).exists((saved, next) => OtpProviderDeliverySettings.sameEndpoint(saved, next)),
      )
      // On a first save there is nothing to keep and the column is simply NULL.
      sql"""
        INSERT INTO delivery_settings (id, smtp, smtp_password, otp_provider, otp_provider_password)
        VALUES (1, ${write.smtp}, $smtpPassword, ${write.otpProvider}, $otpProviderPassword)
        ON CONFLICT (id) DO UPDATE SET
          smtp = EXCLUDED.smtp,
          smtp_password = CASE WHEN $keepSmtp THEN delivery_settings.smtp_password ELSE EXCLUDED.smtp_password END,
          otp_provider = EXCLUDED.otp_provider,
          otp_provider_password = CASE WHEN $keepOtpProvider THEN delivery_settings.otp_provider_password ELSE EXCLUDED.otp_provider_password END
      """.update.run()
    .unit

  override def delete(): Task[Unit] =
    xa.connectMeasured("delete-delivery-settings"):
      sql"""DELETE FROM delivery_settings WHERE id = 1""".update.run()
    .unit

  /** Whether the stored value is kept, and the value written otherwise. */
  private def split(password: DeliverySettingsRepository.StoredPassword): (Boolean, Option[Secret]) =
    password match
      case DeliverySettingsRepository.StoredPassword.Keep => (true, None)
      case DeliverySettingsRepository.StoredPassword.Clear => (false, None)
      case DeliverySettingsRepository.StoredPassword.Set(ciphertext) => (false, Some(ciphertext))

object PostgresDeliverySettingsRepository:
  def live: ZLayer[TransactorZIO, Nothing, DeliverySettingsRepository] =
    ZLayer.fromFunction(PostgresDeliverySettingsRepository(_))
