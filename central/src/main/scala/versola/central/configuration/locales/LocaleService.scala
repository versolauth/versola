package versola.central.configuration.locales

import zio.json.JsonCodec
import zio.schema.{Schema, derived}
import zio.{Task, ZIO, ZLayer}

trait LocaleService:
  def getAll: Task[Vector[LocaleRecord]]
  def getActive: Task[Vector[LocaleRecord]]
  def update(add: Vector[LocaleRecord], delete: Vector[String]): Task[Unit]
  def setDefault(code: String): Task[Either[SetDefaultLocaleError, Unit]]

object LocaleService:
  def live: ZLayer[LocaleRepository, Throwable, LocaleService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(
      repository: LocaleRepository,
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

enum SetDefaultLocaleError derives Schema, JsonCodec:
  case NotFound
  case Inactive
