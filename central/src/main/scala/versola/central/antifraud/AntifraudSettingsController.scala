package versola.central.antifraud

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.util.http.{BadRequest, Controller, EntityVersion, Observability}
import zio.*
import zio.http.{Header, Method, Request, Response, Status, Routes, handler}
import zio.json.EncoderOps

/** The admin API of the antifraud settings (see [[AntifraudSettingsService]]). The caller is known as for
  * [[AntifraudListController]]: edge injects `userId`, as a query parameter of a `GET` and a body field of any other call.
  * Changing the settings is the operator's (`antifraud:operate`); reading them is `antifraud:read`.
  *
  * ==Versions==
  * A read answers the settings with `version` next to them and the same number as the `ETag`. A `PUT` is made on a version:
  * the `If-Match` header (preferred, it is what the `ETag` is for) or the `version` field of the body; when both are given they
  * must agree. A `PUT` that names none is `428`; one on a version the settings have left is `412`, with the current version
  * as the `ETag`. A `DELETE` (the reset) may name a version and does not have to: it is the way back from a bad state. Any
  * change moves the version.
  */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & ResourceService & AntifraudSettingsService

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, deleteEndpoint)

  private def versioned(settings: VersionedAntifraudSettings): Response =
    Response.json(settings.toJson).addHeader(Header.Custom("ETag", EntityVersion.etag(settings.version)))

  val getEndpoint =
    Method.GET / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.get.map(versioned))
      yield response
    }

  val putEndpoint =
    Method.PUT / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[PutAntifraudSettingsRequest]
        expected <- expectedVersion(request, body.version)
        service <- ZIO.service[AntifraudSettingsService]
        response <- expected match
          case None =>
            Observability.setError("antifraud_settings_version_required").as(
              Response.text("a change names the version it was made on: If-Match, or version in the body").status(Status.PreconditionRequired),
            )
          case some => guarded(service.put(body.copy(version = some)).map(versioned))
      yield response
    }

  /** Puts everything off. The caller is in the body, where edge puts it for a request that is not a `GET`. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[DeleteAntifraudSettingsRequest]
        expected <- expectedVersion(request, None)
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.reset(body, expected).as(Response.status(Status.NoContent)))
      yield response
    }

  /** The version the request names, in `If-Match` or in the body; a header that is not a version is a bad request. */
  private def expectedVersion(request: Request, field: Option[Long]): IO[BadRequest, Option[Long]] =
    ZIO.fromEither(EntityVersion.expected(request.headers.get("If-Match"), field)).mapError(BadRequest(_))

  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    AntifraudGuard("invalid_antifraud_settings", "antifraud_settings_not_found")(effect)
