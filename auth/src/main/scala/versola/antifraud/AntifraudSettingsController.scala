package versola.antifraud

import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.CoreConfig
import versola.util.http.{Controller, EntityVersion, Observability}
import zio.*
import zio.http.{Header, Method, Request, Response, Routes, Status, handler}
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec, JsonEncoder}

/** The internal API of [[AntifraudTuningStore]], called by central, which owns the admin API: auth only trusts the internal
  * token. `userId` is the admin who asked, which edge put in central's request from the verified token; it is recorded as
  * who made the change.
  *
  * ==Versions==
  * The row has a version. A read gives it as the `version` field next to the settings and as the `ETag`; a change (`PUT`, and
  * `DELETE` if it names one) is made on a version, as the `If-Match` header or, from a client that cannot set one, the
  * `version` field of the body (the header is preferred; both given must agree). A `PUT` that names none is `428`, one on a
  * version the row has left is `412` with the current version in its `ETag`. A reset is not required to name a version: it
  * is the way back from a bad state, and it moves the version as any change does.
  */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudTuningStore

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, deleteEndpoint)

  /** The settings flat, as they always were, with `version` added. */
  private given JsonEncoder[StoredTuning] = JsonEncoder[Json].contramap: row =>
    row.tuning.toJsonAST.toOption
      .collect { case obj: Json.Obj => obj.add("version", Json.Num(row.version)) }
      .getOrElse(Json.Null)

  private def versioned(row: StoredTuning): Response =
    Response.json(row.toJson).addHeader(Header.Custom("ETag", EntityVersion.etag(row.version)))

  /** The row as stored, which is what applies (everything off where there is none, at version 0). */
  val getEndpoint =
    Method.GET / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        tuning <- ZIO.service[AntifraudTuningStore]
        stored <- tuning.stored
      yield versioned(stored)
    }

  /** Replaces the settings with the body's. */
  val putEndpoint =
    Method.PUT / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[SettingsPayload]
        tuning <- ZIO.service[AntifraudTuningStore]
        response <- guarded:
          withVersion(request, body.version): expected =>
            expected match
              case None => Observability.setError("antifraud_settings_version_required").as(missingVersion)
              case some => tuning.put(body.settings, body.userId.toString, some).map(version => versioned(StoredTuning(body.settings, version)))
      yield response
    }

  /** Puts everything off. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        userId <- request.queryZIO[UserId]("userId")
        tuning <- ZIO.service[AntifraudTuningStore]
        response <- guarded:
          withVersion(request, None): expected =>
            tuning.reset(userId.toString, expected).map: version =>
              Response.status(Status.NoContent).addHeader(Header.Custom("ETag", EntityVersion.etag(version)))
      yield response
    }

  private def missingVersion: Response =
    Response.text("a change names the version it was made on: If-Match, or version in the body").status(Status.PreconditionRequired)

  /** The version the request names, or a 400 for one that does not parse. */
  private def withVersion(request: Request, field: Option[Long])(
      use: Option[Long] => Task[Response],
  ): Task[Response] =
    EntityVersion.expected(request.headers.get("If-Match"), field) match
      case Left(message) => Observability.setError("invalid_antifraud_settings_version", Some(message)).as(Response.text(message).status(Status.BadRequest))
      case Right(expected) => use(expected)

  private def guarded[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Throwable, Response] =
    effect.catchSome:
      case e: InvalidTuning =>
        Observability.setError("invalid_antifraud_settings", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))
      case e: VersionMismatch =>
        Observability.setError("antifraud_settings_version_mismatch", Some(e.getMessage)).as(
          Response.text(e.getMessage).status(Status.PreconditionFailed).addHeader(Header.Custom("ETag", EntityVersion.etag(e.current))),
        )

  /** `version` is the version the settings were read at, for a client that does not send `If-Match`. */
  case class SettingsPayload(settings: Tuning, userId: UserId, version: Option[Long] = None) derives JsonCodec
