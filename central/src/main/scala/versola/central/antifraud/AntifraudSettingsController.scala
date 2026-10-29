package versola.central.antifraud

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.users.UserId
import versola.util.http.{BadRequest, Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

/** The admin API of the antifraud settings (see [[AntifraudSettingsService]]). The caller is known as for
  * [[AntifraudListController]]: edge injects `userId`, as a query parameter of a `GET` and a body field of any other call. */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & ResourceService & AntifraudSettingsService

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, deleteEndpoint)

  val getEndpoint =
    Method.GET / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.get.map(settings => Response.json(settings.toJson)))
      yield response
    }

  val putEndpoint =
    Method.PUT / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[PutAntifraudSettingsRequest]
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.put(body).map(settings => Response.json(settings.toJson)))
      yield response
    }

  /** Puts everything off. The caller is in the body, where edge puts it for a request that is not a `GET`. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[DeleteAntifraudSettingsRequest]
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.reset(body).as(Response.status(Status.NoContent)))
      yield response
    }

  /** What auth refused becomes the answer through the usual mechanism: a [[BadRequest]] that
    * `Observability.handleErrors` turns into a 400, and the outcome in the request's error context. */
  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    effect.catchAll:
      case AntifraudListError.Invalid(message) => Observability.setError("invalid_antifraud_settings", Some(message)) *> ZIO.fail(BadRequest(message))
      case AntifraudListError.NotFound => Observability.setError("antifraud_settings_not_found").as(Response.status(Status.NotFound))
      case error: Throwable => ZIO.fail(error)
