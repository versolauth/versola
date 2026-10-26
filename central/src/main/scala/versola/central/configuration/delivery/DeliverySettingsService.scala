package versola.central.configuration.delivery

import versola.central.CentralConfig
import versola.util.{CacheSource, ReloadingCache, Secret, SecurityService}
import zio.json.JsonCodec
import zio.schema.{Schema, derived}
import zio.{Scope, Task, ZIO, ZLayer}

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import scala.util.control.NoStackTrace

trait DeliverySettingsService:
  /** The stored record with its passwords decrypted; `None` until the first save. */
  def getSettings: Task[Option[DeliverySettingsRecord]]

  /** Fails with [[DeliverySettingsService.ValidationError]] when the update cannot be stored;
    * nothing is written then.
    */
  def upsertSettings(update: DeliverySettingsService.Update): Task[Unit]

  def sync(): Task[Unit]

object DeliverySettingsService:

  /** What the admin API stores. The console never sees a stored password, so a password left out
    * keeps the one on record and the rest of the block can still be saved; an empty one clears
    * it. A block left out is removed together with its password.
    */
  case class Update(
      smtp: Option[SmtpDeliverySettings],
      smtpPassword: Option[String],
      otpProvider: Option[OtpProviderDeliverySettings],
      otpProviderPassword: Option[String],
  ) derives Schema, JsonCodec

  case class ValidationError(message: String) extends RuntimeException(message), NoStackTrace

  def live: ZLayer[DeliverySettingsRepository & SecurityService & CentralConfig & Scope, Throwable, DeliverySettingsService] =
    ZLayer:
      for
        repository <- ZIO.service[DeliverySettingsRepository]
        securityService <- ZIO.service[SecurityService]
        config <- ZIO.service[CentralConfig]
        source = decryptingSource(repository, securityService, config)
        cache <- ReloadingCache
          .make[Option[DeliverySettingsRecord]](config.configurationCacheRefreshInterval)
          .provideSomeLayer[Scope](ZLayer.succeed(source))
      yield Impl(cache = cache, source = source, repository = repository, securityService = securityService, config = config)

  private def secretsKey(config: CentralConfig): SecretKey =
    SecretKeySpec(config.clientSecretsSecret, "AES")

  /** The repository's record with its passwords decrypted, which is what the cache serves. */
  private def decryptingSource(
      repository: DeliverySettingsRepository,
      securityService: SecurityService,
      config: CentralConfig,
  ): CacheSource[Option[DeliverySettingsRecord]] =
    new CacheSource[Option[DeliverySettingsRecord]]:
      private val key = secretsKey(config)

      override def getAll: Task[Option[DeliverySettingsRecord]] =
        repository.getAll.flatMap(ZIO.foreach(_)(decrypt))

      private def decrypt(record: DeliverySettingsRecord): Task[DeliverySettingsRecord] =
        for
          smtpPassword <- ZIO.foreach(record.smtpPassword)(s => securityService.decryptAes256(s, key).map(Secret(_)))
          otpProviderPassword <- ZIO.foreach(record.otpProviderPassword)(s => securityService.decryptAes256(s, key).map(Secret(_)))
        yield record.copy(smtpPassword = smtpPassword, otpProviderPassword = otpProviderPassword)

  class Impl(
      cache: ReloadingCache[Option[DeliverySettingsRecord]],
      source: CacheSource[Option[DeliverySettingsRecord]],
      repository: DeliverySettingsRepository,
      securityService: SecurityService,
      config: CentralConfig,
  ) extends DeliverySettingsService:
    private val key = secretsKey(config)

    override def getSettings: Task[Option[DeliverySettingsRecord]] =
      cache.get

    override def upsertSettings(update: Update): Task[Unit] =
      for
        _ <- ZIO.foreachDiscard(update.smtp.flatMap(SmtpDeliverySettings.validate))(reason => ZIO.fail(ValidationError(reason)))
        _ <- ZIO.foreachDiscard(update.otpProvider.flatMap(OtpProviderDeliverySettings.validate))(reason => ZIO.fail(ValidationError(reason)))
        stored <- repository.getAll
        smtpPassword <- password(update.smtp, update.smtpPassword, stored.flatMap(_.smtpPassword))
        otpProviderPassword <- password(update.otpProvider, update.otpProviderPassword, stored.flatMap(_.otpProviderPassword))
        _ <- repository.upsert(
          DeliverySettingsRecord(
            smtp = update.smtp,
            smtpPassword = smtpPassword,
            otpProvider = update.otpProvider,
            otpProviderPassword = otpProviderPassword,
          ),
        )
      yield ()

    /** The password a block is stored with. A kept password is the ciphertext already on record,
      * passed on as it is; only a new one is encrypted here.
      */
    private def password(block: Option[?], supplied: Option[String], stored: Option[Secret]): Task[Option[Secret]] =
      (block, supplied) match
        case (None, _) => ZIO.none
        case (Some(_), None) => ZIO.succeed(stored)
        case (Some(_), Some("")) => ZIO.none
        case (Some(_), Some(password)) => securityService.encryptAes256(Secret.fromString(password), key).map(bytes => Some(Secret(bytes)))

    override def sync(): Task[Unit] =
      source.getAll.flatMap(cache.set)
