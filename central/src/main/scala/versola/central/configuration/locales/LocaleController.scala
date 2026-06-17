package versola.central.configuration.locales

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object LocaleController extends Controller:
  type Env = Tracing & LocaleService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getLocalesEndpoint,
    updateLocalesEndpoint,
    setDefaultLocaleEndpoint,
    getLocalesSyncEndpoint,
  )

  val getLocalesEndpoint =
    Method.GET / "configuration" / "locales" -> handler { (_: Request) =>
      for
        service <- ZIO.service[LocaleService]
        locales <- service.getAll
      yield Response.json(GetLocalesResponse(locales).toJson)
    }

  val updateLocalesEndpoint =
    Method.PUT / "configuration" / "locales" -> handler { (request: Request) =>
      for
        service <- ZIO.service[LocaleService]
        body    <- request.body.asJson[UpdateLocalesRequest]
        _       <- service.update(body.add, body.delete)
      yield Response.status(Status.NoContent)
    }

  val setDefaultLocaleEndpoint =
    Method.PUT / "configuration" / "locales" / "default" -> handler { (request: Request) =>
      for
        service <- ZIO.service[LocaleService]
        body    <- request.body.asJson[SetDefaultLocaleRequest]
        result  <- service.setDefault(body.code)
      yield result match
        case Right(_)    => Response.status(Status.NoContent)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val getLocalesSyncEndpoint =
    Method.GET / "configuration" / "locales" / "sync" -> handler { (request: Request) =>
      for
        _        <- authorizeInternal(request)
        service  <- ZIO.service[LocaleService]
        active   <- service.getActive
        default   = active.find(_.isDefault).map(_.code).getOrElse("en")
        response  = GetLocalesSyncResponse(
          locales = active.map(locale => SyncLocaleRecord(locale.code, locale.name)),
          default = default,
        )
      yield Response.json(response.toJson)
    }
