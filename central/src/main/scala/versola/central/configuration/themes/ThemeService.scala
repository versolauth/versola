package versola.central.configuration.themes

import versola.central.CentralConfig
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import versola.util.theme.{ThemeCss, ThemePresets}
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

import java.sql.SQLException

trait ThemeService:
  def getAllThemes: Task[Vector[ThemeRecord]]
  def getThemes(tenantId: TenantId): Task[Vector[ThemeRecord]]
  def createTheme(theme: ThemeRecord): Task[Unit]
  def updateTheme(theme: ThemeRecord): Task[Unit]
  def deleteTheme(id: String): Task[Unit]

  /** The stylesheet a theme built from these tokens has, for the console's preview. Fails with an
    * [[IllegalArgumentException]] when the tokens name a base that is not built in.
    */
  def render(request: RenderThemeRequest): Task[RenderThemeResponse]
  def sync(): Task[Unit]

object ThemeService:
  val DefaultThemeId = "default"

  final class ThemeInUseError
    extends RuntimeException("Theme is in use by one or more clients and cannot be deleted")

  def live: ZLayer[ThemeRepository & Scope & CentralConfig, Throwable, ThemeService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[ThemeRecord]](config.configurationCacheRefreshInterval),
      )
    )
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
      validated(theme) *> repository.create(theme)

    override def updateTheme(theme: ThemeRecord): Task[Unit] =
      validated(theme) *> repository.update(theme)

    override def render(request: RenderThemeRequest): Task[RenderThemeResponse] =
      for
        resolved <- ZIO.fromEither(ThemePresets.resolve(request.tokens)).mapError(IllegalArgumentException(_))
        defaultCss <- cache.get.map(_.find(_.id == DefaultThemeId).fold("")(_.css))
      yield RenderThemeResponse(
        css = ThemeCss.compose(defaultCss, resolved, request.css),
        generated = ThemeCss.generate(resolved),
      )

    // The tokens are written into a stylesheet, so a base that is not built in is refused here, and the default
    // theme, which every token theme is laid over, cannot itself be built from tokens.
    private def validated(theme: ThemeRecord): Task[Unit] =
      theme.tokens match
        case None => ZIO.unit
        case Some(_) if theme.id == DefaultThemeId =>
          ZIO.fail(IllegalArgumentException("The default theme is a stylesheet and cannot be built from tokens"))
        case Some(tokens) =>
          ZIO.fromEither(ThemePresets.resolve(tokens)).mapError(IllegalArgumentException(_)).unit

    override def deleteTheme(id: String): Task[Unit] =
      if id == DefaultThemeId then
        ZIO.fail(new IllegalArgumentException("Cannot delete the default theme"))
      else
        repository.delete(id).catchSome:
          case e if isForeignKeyViolation(e) => ZIO.fail(new ThemeInUseError)

    override def sync(): Task[Unit] =
      repository.getAll.flatMap(themes => cache.set(themes))

    private def isForeignKeyViolation(error: Throwable): Boolean =
      error match
        case e: SQLException => Option(e.getSQLState).contains("23503")
        case _ => Option(error.getCause).exists(isForeignKeyViolation)
