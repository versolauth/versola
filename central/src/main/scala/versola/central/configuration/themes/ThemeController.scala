package versola.central.configuration.themes

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import versola.util.theme.{ThemePresets, ThemeVariables}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object ThemeController extends AuditedController:
  type Env = Tracing & ThemeService & ResourceService & CentralConfig & EdgeService

  def endpoints = List(
    getAllThemesEndpoint,
    getThemePresetsEndpoint,
    getThemeVariablesEndpoint,
    renderThemeEndpoint,
    syncThemesEndpoint,
    createThemeEndpoint,
    updateThemeEndpoint,
    deleteThemeEndpoint,
  )

  val getAllThemesEndpoint =
    (Method.GET / "configuration" / "themes").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        themes <- service.getThemes(tenantId)
      yield Response.json(GetAllThemesResponse(themes).toJson)
    }

  /** The built-in themes a theme can be based on. */
  val getThemePresetsEndpoint =
    (Method.GET / "configuration" / "themes" / "presets").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for _ <- authorizeBasic(request)
      yield Response.json(GetThemePresetsResponse(ThemePresets.all).toJson)
    }

  /** The tokens a theme may set one by one: the groups, and for each its kind, whether it takes a light and a dark value, a
    * description in each language and its default. Fixed by the build, like the presets.
    */
  val getThemeVariablesEndpoint =
    (Method.GET / "configuration" / "themes" / "variables").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for _ <- authorizeBasic(request)
      yield Response.json(ThemeVariables.catalog.toJson)
    }

  /** The stylesheet of a theme built from the given tokens; nothing is stored. */
  val renderThemeEndpoint =
    (Method.POST / "configuration" / "themes" / "css").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        body <- request.bodyAs[RenderThemeRequest]
        rendered <- service.render(body)
      yield Response.json(rendered.toJson)).catchSome(badRequest)
    }

  val syncThemesEndpoint =
    (Method.GET / "configuration" / "themes" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[ThemeService]
        themes <- service.getAllThemes
      yield Response.json(GetAllThemesResponse(themes).toJson)
    }

  val createThemeEndpoint =
    (Method.POST / "configuration" / "themes").audited(Audit.change("theme", Audit.create)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        body <- request.bodyAs[CreateThemeRequest]
        _ <- Audit.targetByText(body.id)
        _ <- Audit.tenant(body.tenantId)
        _ <- service.createTheme(ThemeRecord(body.id, body.css, body.tenantId, body.tokens))
      yield Response.status(Status.Created)).catchSome(badRequest)
    }

  val updateThemeEndpoint =
    (Method.PUT / "configuration" / "themes").audited(Audit.change("theme", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        body <- request.bodyAs[UpdateThemeRequest]
        _ <- Audit.targetByText(body.id)
        _ <- Audit.changed(body, "id")
        _ <- service.updateTheme(ThemeRecord(body.id, body.css, None, body.tokens))
      yield Response.status(Status.NoContent)).catchSome(badRequest)
    }

  val deleteThemeEndpoint =
    (Method.DELETE / "configuration" / "themes").audited(Audit.change("theme", Audit.delete)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ThemeService]
        id <- request.url.queryZIO[String]("id")
        _ <- Audit.targetByText(id)
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

  // What a refused theme answers: the reason, as a 400. The messages come from ThemeService and ThemePresets and are
  // fixed sentences (the list of built-in themes at most): the request's own text, such as a base id, is never put into them.
  private val badRequest: PartialFunction[Throwable, ZIO[Any, Nothing, Response]] =
    case e: IllegalArgumentException =>
      ZIO.succeed(Response.json(zio.json.ast.Json.Obj("message" -> zio.json.ast.Json.Str(e.getMessage)).toJson).status(Status.BadRequest))
