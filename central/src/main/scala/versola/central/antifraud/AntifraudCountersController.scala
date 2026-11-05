package versola.central.antifraud

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.util.http.Controller
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

/** The admin API that clears antifraud counters (see [[AntifraudCountersService]]). The operator's (`antifraud:operate`):
  * clearing a range or a phone is deployment-wide and lets traffic through that the counters were holding back. The caller is
  * known as for the other antifraud endpoints: edge puts `userId` in the body. */
object AntifraudCountersController extends AuditedController:
  type Env = Tracing & ResourceService & AntifraudCountersService

  def endpoints = List(resetEndpoint)

  /** Answers `202` with the generation auth gave the reset: it is applied by every instance within the tuning refresh
    * interval, not before this returns. */
  val resetEndpoint =
    (Method.POST / "antifraud" / "counters" / "reset").audited(Audit.change("antifraud-counters", Audit.action("reset"), Audit.Nowhere)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          body <- request.bodyAs[ResetCountersRequest]
          service <- ZIO.service[AntifraudCountersService]
          response <- AntifraudGuard("invalid_counters_reset", "antifraud_counters_not_found")(
            service.reset(body).map(reset => Response.json(reset.toJson).status(Status.Accepted)),
          )
        yield response
    }
