package versola.central.configuration.forms

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec}

object FormController extends Controller:
  type Env = Tracing & FormService & FormTranslationService & ResourceService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllFormsEndpoint,
    getFormPreviewsEndpoint,
    syncFormsEndpoint,
    updateFormEndpoint,
    setActiveVersionEndpoint,
    getTranslationsEndpoint,
    putTranslationEndpoint,
    deleteTranslationEndpoint,
  )

  val getAllFormsEndpoint =
    Method.GET / "configuration" / "forms" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        forms <- service.getAllForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  /** The states of the built-in forms the console previews, with realistic data; see [[FormPreviews]]. */
  val getFormPreviewsEndpoint =
    Method.GET / "configuration" / "forms" / "previews" -> handler { (request: Request) =>
      for _ <- authorizeBasic(request)
      yield Response.json(FormPreviews.response.toJson)
    }

  val syncFormsEndpoint =
    Method.GET / "configuration" / "forms" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[FormService]
        forms <- service.getSyncForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  val updateFormEndpoint =
    Method.PUT / "configuration" / "forms" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        body <- request.bodyAs[UpdateFormRequest]
        _ <- service.updateForm(body.id, body.style, body.jsSource, body.jsCompiled, body.localizations, body.properties, activate = false)
      yield Response.status(Status.NoContent)).catchSome(badRequest)
    }

  val setActiveVersionEndpoint =
    Method.PUT / "configuration" / "forms" / "active" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        body <- request.bodyAs[SetActiveVersionRequest]
        _ <- service.setActiveVersion(body.id, body.version)
      yield Response.status(Status.NoContent)
    }

  /** The translations stored apart from the forms, which `auth` is served laid over each form's own texts. */
  val getTranslationsEndpoint =
    Method.GET / "configuration" / "forms" / "translations" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormTranslationService]
        translations <- service.getAll
      yield Response.json(GetFormTranslationsResponse(translations).toJson)
    }

  val putTranslationEndpoint =
    Method.PUT / "configuration" / "forms" / "translations" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormTranslationService]
        body <- request.bodyAs[PutFormTranslationRequest]
        _ <- service.put(body.formId, body.locale, body.texts)
      yield Response.status(Status.NoContent)).catchSome(badRequest)
    }

  val deleteTranslationEndpoint =
    Method.DELETE / "configuration" / "forms" / "translations" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormTranslationService]
        formId <- request.url.queryZIO[String]("formId")
        locale <- request.url.queryZIO[String]("locale")
        _ <- service.delete(FormId(formId), locale)
      yield Response.status(Status.NoContent)
    }

  // What a refused version answers: the reason, as a 400 with the `{"message": ...}` shape the themes use. The reason
  // names keys and language codes from the request, which Json.Str encodes, so it cannot break out of the string.
  private val badRequest: PartialFunction[Throwable, ZIO[Any, Nothing, Response]] =
    case e: IllegalArgumentException =>
      ZIO.succeed(Response.json(Json.Obj("message" -> Json.Str(e.getMessage)).toJson).status(Status.BadRequest))
