package versola.central.antifraud

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.users.UserId
import versola.util.http.{BadRequest, Controller, EntityVersion, Observability}
import zio.*
import zio.http.{Header, MediaType, Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.ast.Json

/** The admin API of the antifraud settings (see [[AntifraudSettingsService]]). The caller is known as for
  * [[AntifraudListController]]: edge injects `userId`, as a query parameter of a `GET` and a body field of any other call.
  * Changing the settings is the operator's (`antifraud:operate`); reading them is `antifraud:read`.
  *
  * ==Versions==
  * A read answers the settings with `version` next to them and the same number as the `ETag`. A `PUT` is made on a version:
  * the `If-Match` header (preferred, it is what the `ETag` is for) or the `version` field of the body; when both are given they
  * must agree. Naming a version is optional for every change: one made on a version the settings have left is `412`, with
  * the current version as the `ETag`; one that names none overwrites what is there (a `PUT` without one is the declared
  * desired state of a caller that holds it and takes responsibility for what it overwrites, a `DELETE` is the way back from a
  * bad state, a `PATCH` is made again once by auth on what a concurrent change wrote). Any change moves the version.
  *
  * `PATCH` is a JSON merge patch (RFC 7396, `application/merge-patch+json`, `application/json` too): the body is a partial
  * settings document, merged by auth into the stored one and validated as a whole. Edge adds `userId` to the body like any other
  * change, so it is taken out of the patch here (as is `version`, which names the version the patch is made on).
  */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & ResourceService & AntifraudSettingsService

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, patchEndpoint, deleteEndpoint)

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
        response <- guarded(service.put(body.copy(version = expected)).map(versioned))
      yield response
    }

  /** Changes the settings by the merge patch in the body; answers all the settings, with their new version. */
  val patchEndpoint =
    Method.PATCH / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        response <-
          if mergePatchType(request) then patch(request)
          else
            Observability.setError("unsupported_media_type", Some("a settings patch is application/merge-patch+json or application/json")).as(
              Response.status(Status.UnsupportedMediaType),
            )
      yield response
    }

  private def patch(request: Request): ZIO[AntifraudSettingsService, Throwable, Response] =
    for
      document <- request.bodyAs[Json.Obj]
      userId <- ZIO.fromEither(
        document.get("userId").toRight("userId is missing: edge adds it from the verified token").flatMap(_.as[UserId]),
      ).mapError(BadRequest(_))
      inBody <-
        ZIO.fromEither(document.get("version").fold[Either[String, Option[Long]]](Right(None))(_.as[Long].map(Some(_)))).mapError(BadRequest(_))
      expected <- expectedVersion(request, inBody)
      patch = Json.Obj(document.fields.filterNot((name, _) => name == "userId" || name == "version"))
      service <- ZIO.service[AntifraudSettingsService]
      response <- guarded(service.patch(PatchAntifraudSettingsRequest(patch = patch, userId = userId, version = expected)).map(versioned))
    yield response

  /** RFC 7396 §3: `application/merge-patch+json`. `application/json` is taken too, for a client that does not know the type;
    * a body of any other type is not a patch. No type at all is taken as JSON, as for the other endpoints. */
  private def mergePatchType(request: Request): Boolean =
    request.header(Header.ContentType).forall(contentType =>
      contentType.mediaType == MediaType.application.json || contentType.mediaType.fullType == "application/merge-patch+json",
    )

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
