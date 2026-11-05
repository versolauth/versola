package versola.central.configuration.forms

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec}

object FormController extends AuditedController:
  type Env = Tracing & FormService & ResourceService & CentralConfig & EdgeService

  def endpoints = List(
    getAllFormsEndpoint,
    getFormPreviewsEndpoint,
    syncFormsEndpoint,
    updateFormEndpoint,
    setActiveVersionEndpoint,
  )

  val getAllFormsEndpoint =
    (Method.GET / "configuration" / "forms").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        forms <- service.getAllForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  /** The states of the built-in forms the console previews, with realistic data; see [[FormPreviews]]. */
  val getFormPreviewsEndpoint =
    (Method.GET / "configuration" / "forms" / "previews").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for _ <- authorizeBasic(request)
      yield Response.json(FormPreviews.response.toJson)
    }

  val syncFormsEndpoint =
    (Method.GET / "configuration" / "forms" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[FormService]
        forms <- service.getSyncForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  val updateFormEndpoint =
    (Method.PUT / "configuration" / "forms").audited(Audit.change("form", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        body <- request.bodyAs[UpdateFormRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.replaced(body, "id")
        _ <- service.updateForm(body.id, body.style, body.jsSource, body.jsCompiled, body.localizations, body.properties, activate = false)
      yield Response.status(Status.NoContent)).catchSome(badRequest)
    }

  val setActiveVersionEndpoint =
    (Method.PUT / "configuration" / "forms" / "active").audited(Audit.change("form", Audit.action("active"))) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[FormService]
          body <- request.bodyAs[SetActiveVersionRequest]
          _ <- Audit.target(body.id)
          _ <- service.setActiveVersion(body.id, body.version)
        yield Response.status(Status.NoContent)
    }

  // What a refused version answers: the reason, as a 400 with the `{"message": ...}` shape the themes use. The reason
  // names keys and language codes from the request, which Json.Str encodes, so it cannot break out of the string.
  private val badRequest: PartialFunction[Throwable, ZIO[Any, Nothing, Response]] =
    case e: IllegalArgumentException =>
      ZIO.succeed(Response.json(Json.Obj("message" -> Json.Str(e.getMessage)).toJson).status(Status.BadRequest))
