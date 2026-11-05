package versola.antifraud

import versola.audit.AuditSupport.*
import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds}
import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.CoreConfig
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

/** The internal API of [[AntifraudCounters]], called by central like [[AntifraudListController]]: auth only trusts the
  * internal token, and `userId` is the admin who asked, which edge put in central's request from the verified token. It
  * is recorded with the reset. */
object AntifraudCountersController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudCounters & AuditEvents

  def routes: Routes[Env, Throwable] = Routes(resetEndpoint)

  /** 202: the instance that took it has forgotten, the others do within two polls of the tuning. */
  val resetEndpoint =
    Method.POST / "antifraud" / "counters" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[ResetPayload]
        counters <- ZIO.service[AntifraudCounters]
        events <- ZIO.service[AuditEvents]
        response <- counters.reset(body.scope, body.valueType, body.value, body.userId.toString)
          // The value (a phone, an address) is not in the event: its kind is.
          .tap(generation =>
            events.log(
              AuditDraft(
                kind = AuthAuditKinds.AntifraudCountersReset,
                outcome = AuditOutcome.success,
                actor = Some((AuthActors.admin, Some(body.userId.toString))),
                details = AuthAuditDetails.AntifraudCountersReset(
                  scope = body.scope.toString,
                  valueType = body.valueType.map(_.toString),
                  generation = generation,
                ),
              ),
            ),
          )
          .map(generation => Response.json(ResetResponse(generation).toJson).status(Status.Accepted))
          .catchSome:
            case e: InvalidCounterReset =>
              Observability.setError("invalid_counter_reset", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))
      yield response
    }

  /** `value` is a phone number, an address or the leading digits of a number, by `valueType`; both are left out to reset all of the scope. */
  case class ResetPayload(scope: CounterScope, valueType: Option[CounterValueType], value: Option[String], userId: UserId) derives JsonCodec

  case class ResetResponse(generation: Long) derives JsonCodec
