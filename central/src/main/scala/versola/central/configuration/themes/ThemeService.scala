package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

import java.sql.SQLException

trait ThemeService:
  def getAllThemes: Task[Vector[ThemeRecord]]
  def getThemes(tenantId: TenantId): Task[Vector[ThemeRecord]]
  def createTheme(theme: ThemeRecord): Task[Unit]
  def updateTheme(theme: ThemeRecord): Task[Unit]
  def deleteTheme(id: String): Task[Unit]
  def sync(): Task[Unit]

object ThemeService:
  val DefaultThemeId = "default"

  final class ThemeInUseError
      extends RuntimeException("Theme is in use by one or more clients and cannot be deleted")

  def live(schedule: Schedule[Any, Any, Any]): ZLayer[ThemeRepository & Scope, Throwable, ThemeService] =
    ZLayer(ReloadingCache.make[Vector[ThemeRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[ThemeRecord]],
      repository: ThemeRepository,
  ) extends ThemeService:

    override def getAllThemes: Task[Vector[ThemeRecord]] =
      cache.get.map(_.sortBy(_.id))

    override def getThemes(tenantId: TenantId): Task[Vector[ThemeRecord]] =
      cache.get.map { themes =>
        themes
          .filter(theme => theme.tenantId.isEmpty || theme.tenantId.contains(tenantId))
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

    private def isForeignKeyViolation(error: Throwable): Boolean =
      error match
        case e: SQLException => Option(e.getSQLState).contains("23503")
        case _               => Option(error.getCause).exists(isForeignKeyViolation)
