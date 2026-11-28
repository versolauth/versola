package versola.antifraud

import versola.user.authorizeInternal
import versola.util.CoreConfig
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

/** What central asks of the antifraud settings before it stores them; internal, auth only trusts the internal token.
  *
  * The settings are central's (one row for every cell, with its version and who changed it), and this cell copies them
  * ([[AntifraudPolicySync]]). What a valid tuning is stays here, with the engine that applies it: the ranges, the names of
  * the rules, the defaults of a setting and how a patch is applied. So central sends a change here first, and stores what
  * this answers.
  */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(checkEndpoint)

  /** The settings the change would make, or 400 with why they are not valid. Nothing is stored. */
  val checkEndpoint =
    Method.POST / "antifraud" / "settings" / "check" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[CheckPayload]
        response <- AntifraudTuningStore.checked(body.settings, body.patch) match
          case Right(tuning) => ZIO.succeed(Response.json(tuning.toJson))
          case Left(e) =>
            Observability.setError("invalid_antifraud_settings", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))
      yield response
    }

  /** `patch` is applied to `settings`, which are everything off where they are left out; a replacement names no patch. */
  case class CheckPayload(settings: Option[Tuning], patch: Option[TuningPatch]) derives JsonCodec
