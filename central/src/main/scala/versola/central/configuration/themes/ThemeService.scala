package versola.central.configuration.themes

import versola.central.CentralConfig
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import versola.util.theme.{ThemeCss, ThemePresets, ThemeVariables}
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

import java.sql.SQLException

trait ThemeService:
  def getAllThemes: Task[Vector[ThemeRecord]]
  def getThemes(tenantId: TenantId): Task[Vector[ThemeRecord]]
  def createTheme(theme: ThemeRecord): Task[Unit]
  def updateTheme(theme: ThemeRecord): Task[Unit]
  def deleteTheme(id: String): Task[Unit]

  /** The stylesheet a theme has, with a client's overrides on top, for the console's preview. Fails with an
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

    // The order auth's `themeCss` uses: the theme's stylesheet, then the client's overrides on top. A theme with CSS only
    // (no tokens) has its own CSS in place of the foundation, and the default preset stands in as the base the overrides
    // are measured against; with no CSS either, it is the foundation, as for a theme auth does not find.
    override def render(request: RenderThemeRequest): Task[RenderThemeResponse] =
      for
        themed <- request.tokens match
          case Some(tokens) =>
            val variables = tokens.variables.getOrElse(Map.empty[String, String])
            for
              resolved <- ZIO.fromEither(ThemePresets.resolve(tokens)).mapError(IllegalArgumentException(_))
              _ <- ZIO.fromEither(ThemeVariables.validate(variables)).mapError(IllegalArgumentException(_))
            yield (
              ThemeCss.compose(ThemeCss.baseStylesheet, resolved, variables, request.css.getOrElse("")),
              Seq(ThemeCss.generate(resolved), ThemeVariables.render(variables)).filter(_.nonEmpty).mkString("\n"),
              Some(resolved),
            )
          case None =>
            val stylesheet = request.css.filter(_.nonEmpty).getOrElse(ThemeCss.baseStylesheet)
            ZIO.succeed((stylesheet, "", ThemePresets.find(ThemePresets.DefaultId).map(_.tokens)))
        (css, generated, base) = themed
        partial = (request.overrides.filterNot(_.isEmpty), base) match
          case (Some(own), Some(resolved)) => ThemeCss.generatePartial(resolved, own)
          case _ => ""
      yield RenderThemeResponse(css = Seq(css, partial).filter(_.nonEmpty).mkString("\n"), generated = generated)

    // The tokens are written into a stylesheet, so a base that is not built in, a variable that is not in the theming API
    // and a value that could leave its declaration are refused here.
    private def validated(theme: ThemeRecord): Task[Unit] =
      theme.tokens match
        case None => ZIO.unit
        case Some(tokens) =>
          ZIO.fromEither(ThemePresets.resolve(tokens)).mapError(IllegalArgumentException(_)).unit *>
            ZIO.fromEither(ThemeVariables.validate(tokens.variables.getOrElse(Map.empty[String, String]))).mapError(IllegalArgumentException(_))

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
