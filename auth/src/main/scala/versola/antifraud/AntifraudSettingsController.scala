package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.CoreConfig
import versola.util.PageLimit
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

/** The internal API of [[AntifraudTuning]], called by central, which owns the admin API: auth only trusts the internal
  * token. `userId` is the admin who asked, which edge put in central's request from the verified token; it is recorded
  * as who changed the settings. `tenantId` names the tenant's row; without it the global one.
  */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudTuning

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, deleteEndpoint, auditEndpoint)

  /** The row as stored (`stored`, absent where there is none) and what applies to the scope (`effective`). */
  val getEndpoint =
    Method.GET / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        _ <- request.queryZIO[UserId]("userId")
        tenant <- request.queryZIO[Option[TenantId]]("tenantId")
        tuning <- ZIO.service[AntifraudTuning]
        stored <- tuning.stored(tenant)
      yield Response.json(SettingsView(tenant, stored, tuning.effective(tenant)).toJson)
    }

  /** Replaces the row of the scope with the body's patch. */
  val putEndpoint =
    Method.PUT / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[SettingsPayload]
        tuning <- ZIO.service[AntifraudTuning]
        response <- guarded:
          tuning.put(body.tenantId, body.settings, body.userId.toString) *>
            ZIO.succeed(Response.json(SettingsView(body.tenantId, Some(body.settings), tuning.effective(body.tenantId)).toJson))
      yield response
    }

  val deleteEndpoint =
    Method.DELETE / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        userId <- request.queryZIO[UserId]("userId")
        tenant <- request.queryZIO[Option[TenantId]]("tenantId")
        tuning <- ZIO.service[AntifraudTuning]
        response <- guarded:
          tuning.delete(tenant, userId.toString).flatMap:
            case true => ZIO.succeed(Response.status(Status.NoContent))
            case false => Observability.setError("antifraud_settings_not_found").as(Response.status(Status.NotFound))
      yield response
    }

  /** `tenantId` narrows to one tenant, `global=true` to the global row. */
  val auditEndpoint =
    Method.GET / "antifraud" / "settings" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        _ <- request.queryZIO[UserId]("userId")
        tenant <- request.queryZIO[Option[TenantId]]("tenantId")
        global <- request.queryZIO[Option[Boolean]]("global")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        tuning <- ZIO.service[AntifraudTuning]
        response <- guarded:
          ZIO.fail(InvalidTuning("tenantId and global exclude each other")).when(global.contains(true) && tenant.isDefined) *>
            tuning
              .audit(if global.contains(true) then Some(None) else tenant.map(Some(_)), limit.getOrElse(PageLimit.Default))
              .map(records => Response.json(records.toJson))
      yield response
    }

  private def guarded[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Throwable, Response] =
    effect.catchSome:
      case e: InvalidTuning =>
        Observability.setError("invalid_antifraud_settings", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))

  case class SettingsView(tenantId: Option[TenantId], stored: Option[TuningPatch], effective: Tuning) derives JsonCodec

  case class SettingsPayload(tenantId: Option[TenantId], settings: TuningPatch, userId: UserId) derives JsonCodec
