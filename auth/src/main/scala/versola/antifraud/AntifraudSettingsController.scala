package versola.antifraud

import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.CoreConfig
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

/** The internal API of [[AntifraudTuningStore]], called by central, which owns the admin API: auth only trusts the internal
  * token. `userId` is the admin who asked, which edge put in central's request from the verified token; it is recorded as
  * who made the change.
  */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudTuningStore

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, deleteEndpoint)

  /** The row as stored, which is what applies (everything off where there is none). */
  val getEndpoint =
    Method.GET / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        tuning <- ZIO.service[AntifraudTuningStore]
        stored <- tuning.stored
      yield Response.json(stored.getOrElse(Tuning.off).toJson)
    }

  /** Replaces the settings with the body's. */
  val putEndpoint =
    Method.PUT / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[SettingsPayload]
        tuning <- ZIO.service[AntifraudTuningStore]
        response <- guarded:
          tuning.put(body.settings, body.userId.toString).as(Response.json(body.settings.toJson))
      yield response
    }

  /** Puts everything off. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        userId <- request.queryZIO[UserId]("userId")
        tuning <- ZIO.service[AntifraudTuningStore]
        response <- guarded(tuning.reset(userId.toString).as(Response.status(Status.NoContent)))
      yield response
    }

  private def guarded[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Throwable, Response] =
    effect.catchSome:
      case e: InvalidTuning =>
        Observability.setError("invalid_antifraud_settings", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))

  case class SettingsPayload(settings: Tuning, userId: UserId) derives JsonCodec
