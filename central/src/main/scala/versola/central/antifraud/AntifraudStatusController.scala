package versola.central.antifraud

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.util.http.Controller
import zio.*
import zio.http.{Method, Request, Response, Routes, handler}
import zio.json.EncoderOps

/** What an operator reads of the antifraud (`antifraud:read`, nothing is told of who read it): the names a rule can be
  * fired under, and what an auth instance says of whether the antifraud is on and has what it needs (see
  * [[AntifraudStatusService]]). */
object AntifraudStatusController extends AuditedController:
  type Env = Tracing & ResourceService & AntifraudStatusService

  def endpoints = List(rulesEndpoint, statusEndpoint)

  val rulesEndpoint =
    (Method.GET / "antifraud" / "rules").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudStatusService]
        response <- guarded(service.rules.map(rules => Response.json(rules.toJson)))
      yield response
    }

  val statusEndpoint =
    (Method.GET / "antifraud" / "status").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudStatusService]
        response <- guarded(service.status.map(status => Response.json(status.toJson)))
      yield response
    }

  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    AntifraudGuard("invalid_antifraud_request", "antifraud_not_found")(effect)
