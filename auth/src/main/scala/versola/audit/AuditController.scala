package versola.audit

import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.http.{Controller, Observability}
import versola.util.{CoreConfig, PageLimit}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

/** The per-user report of the audit log, for central (internal: the service token, like the `/users` endpoints; not public, so not in
  * `auth/open-api/`). Newest first, over a window of the hot retention. Every input is a query parameter, decoded to its type: a value
  * that does not decode is a bad request before the service reads anything.
  *
  * {{{
  * GET /audit/events?userId&from&to&cursor&limit   -> { events, nextCursor, retentionFrom }
  * GET /audit/summary?userId&from&to               -> { window, byKind, byClient, ips, userAgents, sessions, factors, high }
  * }}}
  */
object AuditController extends Controller:
  type Env = Tracing & AuditReportService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    eventsEndpoint,
    summaryEndpoint,
  )

  val eventsEndpoint =
    Method.GET / "audit" / "events" -> handler { (request: Request) =>
      (for
        _ <- authorizeInternal(request)
        userId <- request.url.queryZIO[UserId]("userId")
        from <- request.url.queryZIO[Option[Instant]]("from")
        to <- request.url.queryZIO[Option[Instant]]("to")
        cursor <- request.url.queryZIO[Option[AuditCursor]]("cursor")
        limit <- request.url.queryZIO[Option[PageLimit]]("limit")
        service <- ZIO.service[AuditReportService]
        page <- service.events(userId, from, to, cursor, limit)
      yield Response.json(page.toJson)).catchAll(refuse)
    }

  val summaryEndpoint =
    Method.GET / "audit" / "summary" -> handler { (request: Request) =>
      (for
        _ <- authorizeInternal(request)
        userId <- request.url.queryZIO[UserId]("userId")
        from <- request.url.queryZIO[Option[Instant]]("from")
        to <- request.url.queryZIO[Option[Instant]]("to")
        service <- ZIO.service[AuditReportService]
        report <- service.summary(userId, from, to)
      yield Response.json(report.toJson)).catchAll(refuse)
    }

  /** A window that cannot be served is `400` with the reason as text, and a report that finds no place to read is `503`; an
    * unauthenticated request, an input that does not decode and a failure of the service go on to the global handler. */
  private def refuse(error: Throwable | AuditReportError): ZIO[Any, Throwable, Response] =
    error match
      case AuditReportError.Busy =>
        Observability.setError(AuditReportError.Busy.code).as(
          Response.text(AuditReportError.Busy.message).status(Status.ServiceUnavailable).addHeader(Header.Custom("Retry-After", "1")),
        )
      case error: AuditReportError => Observability.setError(error.code).as(Response.text(error.message).status(Status.BadRequest))
      case error: Throwable => ZIO.fail(error)
