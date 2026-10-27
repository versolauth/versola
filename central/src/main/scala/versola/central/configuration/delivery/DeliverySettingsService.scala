package versola.central.configuration.delivery

import versola.central.CentralConfig
import versola.util.{CacheSource, ReloadingCache, Secret, SecurityService}
import zio.json.JsonDecoder
import zio.{Scope, Task, ZIO, ZLayer}

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

trait DeliverySettingsService:
  /** The stored record with its passwords decrypted; `None` until the first save. */
  def getSettings: Task[Option[DeliverySettingsRecord]]

  /** `Left` when the update cannot be stored, saying which field and rule; nothing is written then. */
  def upsertSettings(update: DeliverySettingsService.Update): Task[Either[DeliverySettingsService.ValidationError, Unit]]

  /** Removes the record, so that auth is back to the blocks of its own `auth.conf`. */
  def deleteSettings(): Task[Unit]

  def sync(): Task[Unit]

object DeliverySettingsService:

  /** What the admin API stores. The console never sees a stored password, so a password left out
    * (or sent as `null`) keeps the one on record and the rest of the block can still be saved; an
    * empty one clears it. A block left out is removed together with its password.
    */
  case class Update(
      smtp: Option[SmtpDeliverySettings],
      smtpPassword: Option[String],
      otpProvider: Option[OtpProviderDeliverySettings],
      otpProviderPassword: Option[String],
  ) derives JsonDecoder // No encoder on purpose: the type holds the plaintext passwords.

  /** Names the field and the rule, never a value, so the message is safe in a response and in the error context. */
  case class ValidationError(message: String)

  def live: ZLayer[DeliverySettingsRepository & SecurityService & CentralConfig & Scope, Throwable, DeliverySettingsService] =
    ZLayer:
      for
        repository <- ZIO.service[DeliverySettingsRepository]
        securityService <- ZIO.service[SecurityService]
        config <- ZIO.service[CentralConfig]
        source = decryptingSource(repository = repository, securityService = securityService, config = config)
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

    override def upsertSettings(update: Update): Task[Either[ValidationError, Unit]] =
      update.smtp.flatMap(SmtpDeliverySettings.validate).orElse(update.otpProvider.flatMap(OtpProviderDeliverySettings.validate)) match
        case Some(reason) => ZIO.left(ValidationError(reason))
        case None => store(update).asRight

    private def store(update: Update): Task[Unit] =
      for
        smtpPassword <- storedPassword(update.smtp, update.smtpPassword)
        otpProviderPassword <- storedPassword(update.otpProvider, update.otpProviderPassword)
        _ <- repository.upsert(
          DeliverySettingsRepository.Write(
            smtp = update.smtp,
            smtpPassword = smtpPassword,
            otpProvider = update.otpProvider,
            otpProviderPassword = otpProviderPassword,
          ),
        )
        _ <- refreshNow
      yield ()

    /** Only a new password is encrypted here; keeping the stored one is left to the repository,
      * which does it in the same statement as the rest of the write.
      */
    private def storedPassword(block: Option[?], supplied: Option[String]): Task[DeliverySettingsRepository.StoredPassword] =
      (block, supplied) match
        case (None, _) => ZIO.succeed(DeliverySettingsRepository.StoredPassword.Clear)
        case (Some(_), None) => ZIO.succeed(DeliverySettingsRepository.StoredPassword.Keep)
        case (Some(_), Some("")) => ZIO.succeed(DeliverySettingsRepository.StoredPassword.Clear)
        case (Some(_), Some(password)) =>
          securityService.encryptAes256(Secret.fromString(password), key).map(bytes => DeliverySettingsRepository.StoredPassword.Set(Secret(bytes)))

    override def deleteSettings(): Task[Unit] =
      repository.delete() *> refreshNow

    /** A write is read back at once, so an admin's next read on this instance shows it instead of waiting for the change
      * notification. A failed read here only costs that: the write is done, and the notification refreshes the cache anyway.
      */
    private def refreshNow: Task[Unit] =
      sync().ignoreLogged

    override def sync(): Task[Unit] =
      source.getAll.flatMap(cache.set)
