package versola.central.configuration.system

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object SystemSettingsController extends Controller:
  type Env = Tracing & SystemSettingsService & CentralConfig & OAuthClientService & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getSystemSettingsEndpoint,
    syncSystemSettingsEndpoint,
    upsertSystemSettingsEndpoint,
  )

  val getSystemSettingsEndpoint =
    Method.GET / "configuration" / "system-settings" -> handler { (request: Request) =>
      for
        _       <- authorizeBasic(request)
        service <- ZIO.service[SystemSettingsService]
        settings <- service.getSettings
      yield Response.json(settings.toJson)
    }

  val syncSystemSettingsEndpoint =
    Method.GET / "configuration" / "system-settings" / "sync" -> handler { (request: Request) =>
      for
        _        <- authorizeInternal(request)
        service  <- ZIO.service[SystemSettingsService]
        settings <- service.getSettings
      yield Response.json(settings.toJson)
    }

  val upsertSystemSettingsEndpoint =
    Method.PUT / "configuration" / "system-settings" -> handler { (request: Request) =>
      for
        _       <- authorizeBasic(request)
        service <- ZIO.service[SystemSettingsService]
        body    <- request.body.asJson[SystemSettingsRecord]
        _       <- service.upsertSettings(body)
      yield Response.status(Status.NoContent)
    }
