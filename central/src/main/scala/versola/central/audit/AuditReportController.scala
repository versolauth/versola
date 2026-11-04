package versola.central.audit

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.users.UserId
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

import java.time.Instant

/** The per-user audit report (`audit:read`, see [[AuditReportService]]). The user is named in the query like everywhere in central's
  * API. Asking is itself an audit event (`admin.audit.queried`), which [[AuditHook]] records. */
object AuditReportController extends Controller:
  type Env = Tracing & ResourceService & AuditReportService

  def routes: Routes[Env, Throwable] = Routes(userReportEndpoint)

  val userReportEndpoint =
    Method.GET / "audit" / "reports" / "user" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        userId <- request.queryZIO[UserId]("userId")
        from <- request.queryZIO[Option[Instant]]("from")
        to <- request.queryZIO[Option[Instant]]("to")
        cursor <- request.queryZIO[Option[String]]("cursor")
        limit <- request.queryZIO[Option[Int]]("limit")
        service <- ZIO.service[AuditReportService]
        response <- service.userReport(userId, from, to, cursor, limit)
          .map(report => Response.json(report.toJson))
          .catchAll:
            case AuditReportError.Invalid(message) =>
              Observability.setError("invalid_audit_query", Some(message)).as(Response.text(message).status(Status.BadRequest))
            case AuditReportError.Busy =>
              Observability.setError("audit_report_busy").as(
                Response.text("too many audit reports are running, try again").status(Status.ServiceUnavailable),
              )
            case cause: Throwable => ZIO.fail(cause)
      yield response
    }
