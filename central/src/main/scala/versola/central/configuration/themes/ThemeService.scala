package versola.central.configuration.themes

import versola.central.CentralConfig
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

import java.sql.SQLException
import scala.io.Source

trait ThemeService:
  def getAllThemes: Task[Vector[ThemeRecord]]
  def getThemes(tenantId: Option[TenantId]): Task[Vector[ThemeRecord]]
  def createTheme(theme: ThemeRecord): Task[Unit]
  def updateTheme(theme: ThemeRecord): Task[Unit]
  def deleteTheme(id: String): Task[Unit]
  def sync(): Task[Unit]

object ThemeService:
  val DefaultThemeId = "default"

  final class ThemeInUseError
      extends RuntimeException("Theme is in use by one or more clients and cannot be deleted")

  def live(schedule: Schedule[Any, Any, Any]): ZLayer[ThemeRepository & CentralConfig & Scope, Throwable, ThemeService] =
    ZLayer(ReloadingCache.make[Vector[ThemeRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _))
      >>> ZLayer(ZIO.serviceWithZIO[ThemeService.Impl](s => s.initialize().as(s)))

  class Impl(
      cache: ReloadingCache[Vector[ThemeRecord]],
      repository: ThemeRepository,
      config: CentralConfig,
  ) extends ThemeService:

    override def getAllThemes: Task[Vector[ThemeRecord]] =
      cache.get.map(_.sortBy(_.id))

    override def getThemes(tenantId: Option[TenantId]): Task[Vector[ThemeRecord]] =
      cache.get.map { themes =>
        tenantId
          .fold(themes)(t => themes.filter(theme => theme.tenantId.isEmpty || theme.tenantId.contains(t)))
          .sortBy(_.id)
      }

    override def createTheme(theme: ThemeRecord): Task[Unit] =
      repository.create(theme) *> sync()

    override def updateTheme(theme: ThemeRecord): Task[Unit] =
      repository.update(theme) *> sync()

    override def deleteTheme(id: String): Task[Unit] =
      if id == DefaultThemeId then
        ZIO.fail(new IllegalArgumentException("Cannot delete the default theme"))
      else
        (repository.delete(id) *> sync()).catchSome:
          case e if isForeignKeyViolation(e) => ZIO.fail(new ThemeInUseError)

    override def sync(): Task[Unit] =
      repository.getAll.flatMap(themes => cache.set(themes))

    def initialize(): Task[Unit] =
      ZIO.when(config.initialize):
        for
          _ <- ZIO.logInfo("Initializing themes from resources...")
          css <- readResource("forms/common.css")
          default = ThemeRecord(DefaultThemeId, css, None)
          _ <- repository.create(default).catchAll(_ => repository.update(default))
          _ <- sync()
        yield ()
      .unit

    private def readResource(path: String): Task[String] =
      ZIO.blocking:
        ZIO.attemptBlocking:
          val source = Source.fromResource(path)
          try source.mkString finally source.close()

    private def isForeignKeyViolation(error: Throwable): Boolean =
      error match
        case e: SQLException => Option(e.getSQLState).contains("23503")
        case _               => Option(error.getCause).exists(isForeignKeyViolation)
