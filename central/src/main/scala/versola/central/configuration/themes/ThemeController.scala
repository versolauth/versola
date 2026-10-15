package versola.central.configuration.themes

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import versola.util.theme.ThemePresets
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object ThemeController extends Controller:
  type Env = Tracing & ThemeService & ResourceService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllThemesEndpoint,
    getThemePresetsEndpoint,
    renderThemeEndpoint,
    syncThemesEndpoint,
    createThemeEndpoint,
    updateThemeEndpoint,
    deleteThemeEndpoint,
  )

  val getAllThemesEndpoint =
    Method.GET / "configuration" / "themes" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        themes <- service.getThemes(tenantId)
      yield Response.json(GetAllThemesResponse(themes).toJson)
    }

  /** The built-in themes a theme can be based on. */
  val getThemePresetsEndpoint =
    Method.GET / "configuration" / "themes" / "presets" -> handler { (request: Request) =>
      for _ <- authorizeBasic(request)
      yield Response.json(GetThemePresetsResponse(ThemePresets.all).toJson)
    }

  /** The stylesheet of a theme built from the given tokens; nothing is stored. */
  val renderThemeEndpoint =
    Method.POST / "configuration" / "themes" / "css" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        body <- request.bodyAs[RenderThemeRequest]
        rendered <- service.render(body)
      yield Response.json(rendered.toJson)).catchSome(badRequest)
    }

  val syncThemesEndpoint =
    Method.GET / "configuration" / "themes" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[ThemeService]
        themes <- service.getAllThemes
      yield Response.json(GetAllThemesResponse(themes).toJson)
    }

  val createThemeEndpoint =
    Method.POST / "configuration" / "themes" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        body <- request.bodyAs[CreateThemeRequest]
        _ <- service.createTheme(ThemeRecord(body.id, body.css, body.tenantId, body.tokens))
      yield Response.status(Status.Created)).catchSome(badRequest)
    }

  val updateThemeEndpoint =
    Method.PUT / "configuration" / "themes" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        body <- request.bodyAs[UpdateThemeRequest]
        _ <- service.updateTheme(ThemeRecord(body.id, body.css, None, body.tokens))
      yield Response.status(Status.NoContent)).catchSome(badRequest)
    }

  val deleteThemeEndpoint =
    Method.DELETE / "configuration" / "themes" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        id <- request.url.queryZIO[String]("id")
        _ <- service.deleteTheme(id)
      yield Response.status(Status.NoContent))
        .catchSome:
          case _: ThemeService.ThemeInUseError =>
            ZIO.succeed:
              Response
                .json("""{"message":"Theme is in use by one or more clients and cannot be deleted"}""")
                .status(Status.Conflict)
          case e: IllegalArgumentException => badRequest(e)
    }

  // What a refused theme answers: the reason, as a 400. The message is ours (a known base, a fixed sentence), never
  // an echo of the request's own text, so it is safe to return.
  private val badRequest: PartialFunction[Throwable, ZIO[Any, Nothing, Response]] =
    case e: IllegalArgumentException =>
      ZIO.succeed(Response.json(zio.json.ast.Json.Obj("message" -> zio.json.ast.Json.Str(e.getMessage)).toJson).status(Status.BadRequest))
