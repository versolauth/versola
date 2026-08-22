package versola.central.configuration.system

import versola.central.CentralConfig
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

import java.net.URI
import scala.util.Try

trait SystemSettingsService:
  def getSettings: Task[SystemSettingsRecord]
  def upsertSettings(record: SystemSettingsRecord): Task[Either[SystemSettingsValidationError, Unit]]
  def sync(): Task[Unit]

object SystemSettingsService:
  def live: ZLayer[SystemSettingsRepository & Scope & CentralConfig, Throwable, SystemSettingsService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[SystemSettingsRecord](Schedule.spaced(config.configurationCacheRefreshInterval)),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[SystemSettingsRecord],
      repository: SystemSettingsRepository,
  ) extends SystemSettingsService:

    override def getSettings: Task[SystemSettingsRecord] =
      cache.get

    override def upsertSettings(record: SystemSettingsRecord): Task[Either[SystemSettingsValidationError, Unit]] =
      normalize(record) match
        case Left(error)       => ZIO.left(error)
        case Right(normalized) => repository.upsert(normalized).as(Right(()))

    override def sync(): Task[Unit] =
      repository.getAll.flatMap(cache.set)

    private def normalize(record: SystemSettingsRecord): Either[SystemSettingsValidationError, SystemSettingsRecord] =
      record.identityProviderLogo match
        case None => Right(record)
        case Some(value) =>
          val logo = value.trim
          if logo.isEmpty then Right(record.copy(identityProviderLogo = None))
          else if isValidLogoUrl(logo) then Right(record.copy(identityProviderLogo = Some(logo)))
          else Left(SystemSettingsValidationError.InvalidIdentityProviderLogo)

    private def isValidLogoUrl(value: String): Boolean =
      Try(URI(value)).toOption.exists: uri =>
        val validScheme = Option(uri.getScheme).exists(scheme => scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        uri.isAbsolute && validScheme && Option(uri.getHost).exists(_.nonEmpty) && uri.getPort <= 65535

enum SystemSettingsValidationError:
  case InvalidIdentityProviderLogo

  def message: String = this match
    case InvalidIdentityProviderLogo => "Identity provider logo must be an absolute HTTP(S) URL"
