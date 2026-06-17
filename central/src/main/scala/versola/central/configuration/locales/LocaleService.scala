package versola.central.configuration.locales

import versola.central.CentralConfig
import zio.json.JsonCodec
import zio.schema.{Schema, derived}
import zio.{Task, ZIO, ZLayer}

trait LocaleService:
  def getAll: Task[Vector[LocaleRecord]]
  def getActive: Task[Vector[LocaleRecord]]
  def update(add: Vector[LocaleRecord], delete: Vector[String]): Task[Unit]
  def setDefault(code: String): Task[Either[SetDefaultLocaleError, Unit]]

object LocaleService:
  private val defaultLocales = Vector(
    LocaleRecord("en", "English", isDefault = true, active = true),
    LocaleRecord("ru", "Russian", isDefault = false, active = true),
  )

  def live: ZLayer[LocaleRepository & CentralConfig, Throwable, LocaleService] =
    ZLayer.fromFunction(Impl(_, _))
      >>> ZLayer(ZIO.serviceWithZIO[LocaleService.Impl](service => service.initialize().as(service)))

  class Impl(
      repository: LocaleRepository,
      config: CentralConfig,
  ) extends LocaleService:

    override def getAll: Task[Vector[LocaleRecord]] =
      repository.getAll

    override def getActive: Task[Vector[LocaleRecord]] =
      repository.getAll.map(_.filter(_.active))

    override def update(add: Vector[LocaleRecord], delete: Vector[String]): Task[Unit] =
      repository.update(add, delete)

    override def setDefault(code: String): Task[Either[SetDefaultLocaleError, Unit]] =
      repository.getAll.flatMap: locales =>
        locales.find(_.code == code) match
          case None                         => ZIO.left(SetDefaultLocaleError.NotFound)
          case Some(locale) if !locale.active => ZIO.left(SetDefaultLocaleError.Inactive)
          case Some(_)                      => repository.setDefault(code).map(Right(_))

    def initialize(): Task[Unit] =
      ZIO.when(config.initialize):
        ZIO.logInfo("Initializing default locales...") *>
          repository.update(add = defaultLocales, delete = Vector.empty)
      .unit

enum SetDefaultLocaleError derives Schema, JsonCodec:
  case NotFound
  case Inactive
