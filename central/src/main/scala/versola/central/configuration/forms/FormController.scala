package versola.central.configuration.forms

import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

object FormController extends Controller:
  type Env = Tracing & FormService & OAuthClientService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllFormsEndpoint,
    syncFormsEndpoint,
    updateFormEndpoint,
    setActiveVersionEndpoint,
  )

  val getAllFormsEndpoint =
    Method.GET / "configuration" / "forms" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        forms <- service.getAllForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  val syncFormsEndpoint =
    Method.GET / "configuration" / "forms" / "sync" -> handler { (request: Request) =>
      for
        _       <- authorizeInternal(request)
        service <- ZIO.service[FormService]
        forms   <- service.getSyncForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  val updateFormEndpoint =
    Method.PUT / "configuration" / "forms" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        body <- request.body.asJson[UpdateFormRequest]
        _ <- service.updateForm(body.id, body.style, body.jsSource, body.jsCompiled, body.localizations, body.properties, activate = false)
      yield Response.status(Status.NoContent)
    }

  val setActiveVersionEndpoint =
    Method.PUT / "configuration" / "forms" / "active" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[FormService]
        body <- request.body.asJson[SetActiveVersionRequest]
        _ <- service.setActiveVersion(body.id, body.version)
      yield Response.status(Status.NoContent)
    }
