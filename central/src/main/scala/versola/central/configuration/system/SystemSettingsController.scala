package versola.central.configuration.system

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import zio.ZIO
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.EncoderOps

object SystemSettingsController extends AuditedController:
  type Env = Tracing & SystemSettingsService & CentralConfig & ResourceService & EdgeService

  def endpoints = List(
    getSystemSettingsEndpoint,
    syncSystemSettingsEndpoint,
    upsertSystemSettingsEndpoint,
  )

  val getSystemSettingsEndpoint =
    (Method.GET / "configuration" / "system-settings").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[SystemSettingsService]
        settings <- service.getSettings
      yield Response.json(settings.toJson)
    }

  val syncSystemSettingsEndpoint =
    (Method.GET / "configuration" / "system-settings" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[SystemSettingsService]
        settings <- service.getSettings
      yield Response.json(settings.toJson)
    }

  val upsertSystemSettingsEndpoint =
    (Method.PUT / "configuration" / "system-settings").audited(Audit.change("system-settings", Audit.update, noTarget = true)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[SystemSettingsService]
          body <- request.bodyAs[SystemSettingsRecord]
          _ <- Audit.replaced(body)
          _ <- service.upsertSettings(body)
        yield Response.status(Status.NoContent)
    }
