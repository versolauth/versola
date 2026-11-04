package versola.audit

import versola.audit.AuditReportService.EventsPage
import versola.user.authorizeInternal
import versola.util.CoreConfig
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** The per-user report of the audit log, for central (internal: the service token, like the `/users` endpoints; not public, so not in
  * `auth/open-api/`). Newest first, over a window of the hot retention.
  *
  * {{{
  * GET /audit/users/{userId}/events?from&to&cursor&limit   -> { events, nextCursor, retentionFrom }
  * GET /audit/users/{userId}/summary?from&to               -> { window, byKind, byClient, ips, userAgents, sessions, factors, high }
  * }}}
  */
object AuditController extends Controller:
  type Env = Tracing & AuditReportService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    eventsEndpoint,
    summaryEndpoint,
  )

  val eventsEndpoint =
    Method.GET / "audit" / "users" / string("userId") / "events" -> handler { (userId: String, request: Request) =>
      (for
        _ <- authorizeInternal(request)
        user <- parseUser(userId)
        from <- instant(request, "from")
        to <- instant(request, "to")
        limit <- limit(request)
        service <- ZIO.service[AuditReportService]
        page <- service.events(user, from, to, request.url.queryParams.getAll("cursor").headOption, limit)
      yield Response.json(page.toJson)).catchAll(refuse)
    }

  val summaryEndpoint =
    Method.GET / "audit" / "users" / string("userId") / "summary" -> handler { (userId: String, request: Request) =>
      (for
        _ <- authorizeInternal(request)
        user <- parseUser(userId)
        from <- instant(request, "from")
        to <- instant(request, "to")
        service <- ZIO.service[AuditReportService]
        report <- service.summary(user, from, to)
      yield Response.json(report.toJson)).catchAll(refuse)
    }

  /** A request that cannot be served is `400` with the reason as text; an unauthenticated one and a failure of the service go on to
    * the global handler. */
  private def refuse(error: Throwable | AuditReportError | BadParameter): ZIO[Any, Throwable, Response] =
    error match
      case AuditReportError.Busy =>
        Observability.setError(AuditReportError.Busy.code).as(
          Response.text(AuditReportError.Busy.message).status(Status.ServiceUnavailable).addHeader(Header.Custom("Retry-After", "1")),
        )
      case error: AuditReportError => Observability.setError(error.code).as(Response.text(error.message).status(Status.BadRequest))
      case BadParameter(name) =>
        Observability.setError("audit_bad_parameter", Some(name)).as(Response.text(s"$name is not valid").status(Status.BadRequest))
      case error: Throwable => ZIO.fail(error)

  private case class BadParameter(name: String)

  private def parseUser(value: String): IO[BadParameter, UUID] =
    ZIO.fromOption(Try(UUID.fromString(value)).toOption).orElseFail(BadParameter("userId"))

  private def instant(request: Request, name: String): IO[BadParameter, Option[Instant]] =
    ZIO.foreach(request.url.queryParams.getAll(name).headOption)(value =>
      ZIO.fromOption(Try(Instant.parse(value)).toOption).orElseFail(BadParameter(name)),
    )

  private def limit(request: Request): IO[BadParameter, Option[Int]] =
    ZIO.foreach(request.url.queryParams.getAll("limit").headOption)(value =>
      ZIO.fromOption(value.toIntOption).orElseFail(BadParameter("limit")),
    )
