package versola.central.configuration.forms

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.ZIO

object FormController extends Controller:
  type Env = Tracing & FormService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllFormsEndpoint,
    syncFormsEndpoint,
    getLocalesEndpoint,
    updateLocalesEndpoint,
    updateFormEndpoint,
    setActiveVersionEndpoint,
  )

  val getAllFormsEndpoint =
    Method.GET / "configuration" / "forms" -> handler { (_: Request) =>
      for
        service <- ZIO.service[FormService]
        forms <- service.getAllForms
      yield Response.json(GetAllFormsResponse(forms).toJson)
    }

  val syncFormsEndpoint =
    Method.GET / "configuration" / "forms" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[FormService]
        forms <- service.getAllForms
      yield Response.json(GetAllFormsResponse(forms.filter(_.active)).toJson)
    }

  val getLocalesEndpoint =
    Method.GET / "configuration" / "forms" / "locales" -> handler { (_: Request) =>
      for
        service <- ZIO.service[FormService]
        locales <- service.getLocales
      yield Response.json(GetFormLocalesResponse(locales).toJson)
    }

  val updateLocalesEndpoint =
    Method.PUT / "configuration" / "forms" / "locales" -> handler { (request: Request) =>
      for
        service <- ZIO.service[FormService]
        body <- request.body.asJson[UpdateFormLocalesRequest]
        _ <- service.updateLocales(body.add, body.delete)
      yield Response.status(Status.NoContent)
    }

  val updateFormEndpoint =
    Method.PUT / "configuration" / "forms" -> handler { (request: Request) =>
    for
      service <- ZIO.service[FormService]
      body <- request.body.asJson[UpdateFormRequest]
      _ <- service.updateForm(body.id, body.style, body.jsSource, body.jsCompiled, body.localizations, body.properties, activate = false)
    yield Response.status(Status.NoContent)
  }

  val setActiveVersionEndpoint =
    Method.PUT / "configuration" / "forms" / "active" -> handler { (request: Request) =>
    for
      service <- ZIO.service[FormService]
      body <- request.body.asJson[SetActiveVersionRequest]
      _ <- service.setActiveVersion(body.id, body.version)
    yield Response.status(Status.NoContent)
  }
