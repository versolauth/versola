package versola.central.configuration.locales

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object LocaleController extends AuditedController:
  type Env = Tracing & LocaleService & ResourceService & CentralConfig & EdgeService

  def endpoints = List(
    getLocalesEndpoint,
    updateLocalesEndpoint,
    setDefaultLocaleEndpoint,
    getLocalesSyncEndpoint,
  )

  val getLocalesEndpoint =
    (Method.GET / "configuration" / "locales").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[LocaleService]
        locales <- service.getAll
      yield Response.json(GetLocalesResponse(locales).toJson)
    }

  val updateLocalesEndpoint =
    (Method.PUT / "configuration" / "locales").audited(Audit.change("locale", Audit.update, Audit.Nowhere)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[LocaleService]
        body <- request.bodyAs[UpdateLocalesRequest]
        _ <- service.update(body.add, body.delete)
      yield Response.status(Status.NoContent)).catchAll:
        case error: LocaleActivationError =>
          ZIO.succeed(Response.json(LocaleActivationError(error.locale, error.missing).toJson).status(Status.BadRequest))
        case error: Throwable => ZIO.fail(error)
    }

  val setDefaultLocaleEndpoint =
    (Method.PUT / "configuration" / "locales" / "default").audited(Audit.change("locale", Audit.action("default"), Audit.body("code"))) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[LocaleService]
          body <- request.bodyAs[SetDefaultLocaleRequest]
          result <- service.setDefault(body.code)
        yield result match
          case Right(_) => Response.status(Status.NoContent)
          case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val getLocalesSyncEndpoint =
    (Method.GET / "configuration" / "locales" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[LocaleService]
        active <- service.getActive
        default = active.find(_.isDefault).map(_.code).getOrElse("en")
        response = GetLocalesSyncResponse(
          locales = active.map(locale => SyncLocaleRecord(locale.code, locale.name)),
          default = default,
        )
      yield Response.json(response.toJson)
    }
