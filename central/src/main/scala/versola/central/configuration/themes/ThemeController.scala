package versola.central.configuration.themes

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler, string}
import zio.json.EncoderOps

object ThemeController extends Controller:
  type Env = Tracing & ThemeService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllThemesEndpoint,
    syncThemesEndpoint,
    createThemeEndpoint,
    updateThemeEndpoint,
    deleteThemeEndpoint,
  )

  val getAllThemesEndpoint =
    Method.GET / "configuration" / "themes" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ThemeService]
        tenantId <- request.url.queryZIO[Option[TenantId]]("tenantId")
        themes <- service.getThemes(tenantId)
      yield Response.json(GetAllThemesResponse(themes).toJson)
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
      for
        service <- ZIO.service[ThemeService]
        body <- request.body.asJson[CreateThemeRequest]
        _ <- service.createTheme(ThemeRecord(body.id, body.css, body.tenantId))
      yield Response.status(Status.Created)
    }

  val updateThemeEndpoint =
    Method.PUT / "configuration" / "themes" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ThemeService]
        body <- request.body.asJson[UpdateThemeRequest]
        _ <- service.updateTheme(ThemeRecord(body.id, body.css, None))
      yield Response.status(Status.NoContent)
    }

  val deleteThemeEndpoint =
    Method.DELETE / "configuration" / "themes" / string("id") -> handler { (id: String, _: Request) =>
      (for
        service <- ZIO.service[ThemeService]
        _ <- service.deleteTheme(id)
      yield Response.status(Status.NoContent))
        .catchSome:
          case _: ThemeService.ThemeInUseError =>
            ZIO.succeed:
              Response
                .json("""{"message":"Theme is in use by one or more clients and cannot be deleted"}""")
                .status(Status.Conflict)
          case e: IllegalArgumentException =>
            ZIO.succeed(Response.json(s"""{"message":"${e.getMessage}"}""").status(Status.BadRequest))
    }
