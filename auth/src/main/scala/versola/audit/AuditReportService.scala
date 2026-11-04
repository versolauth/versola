package versola.audit

import versola.audit.AuditReadRepository.{Position, Summary}
import versola.util.Base64
import versola.util.audit.{AuditEvent, AuditEvents}
import zio.*
import zio.json.{JsonCodec, jsonExplicitNull}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** A request for the per-user report that cannot be served as asked. `code` is the `setError` code. */
enum AuditReportError(val code: String, val message: String):
  case WindowOutsideRetention
    extends AuditReportError("audit_window_outside_retention", "from is before the start of the retained window, or to is in the future")
  case WindowReversed extends AuditReportError("audit_window_reversed", "to is before from")
  case BadLimit extends AuditReportError("audit_bad_limit", "limit must be between 1 and 500")
  case BadCursor extends AuditReportError("audit_bad_cursor", "the cursor is not one this endpoint issued")

/** The per-user report of auth's audit log, which central serves to an administrator: a page of the events about one user, and a
  * summary of them, over a window of the hot retention. Each read is one grouped query per block over the user's index range. */
trait AuditReportService:
  def events(
      userId: UUID,
      from: Option[Instant],
      to: Option[Instant],
      cursor: Option[String],
      limit: Option[Int],
  ): IO[Throwable | AuditReportError, AuditReportService.EventsPage]

  def summary(userId: UUID, from: Option[Instant], to: Option[Instant]): IO[Throwable | AuditReportError, AuditReportService.SummaryReport]

object AuditReportService:
  val DefaultLimit = 100
  val MaxLimit = 500

  /** How far ahead of auth's clock `to` may be: central's clock is not auth's, and `to = now` there is a little later here. */
  val ClockSkew: Duration = 1.minute

  /** `nextCursor` is `null`, not left out, on the last page: central codes against that. */
  case class EventsPage(events: List[AuditEvent], @jsonExplicitNull nextCursor: Option[String], retentionFrom: Instant) derives JsonCodec
  case class Window(from: Instant, to: Instant, retentionFrom: Instant) derives JsonCodec

  case class SummaryReport(
      window: Window,
      byKind: List[AuditReadRepository.KindCount],
      byClient: List[AuditReadRepository.ClientCount],
      ips: List[AuditReadRepository.IpCount],
      userAgents: List[AuditReadRepository.UserAgentCount],
      sessions: AuditReadRepository.SessionCount,
      factors: List[AuditReadRepository.Factor],
      high: List[AuditEvent],
  ) derives JsonCodec

  /** `base64url(at|id)`: opaque to the caller, and the only thing the next page needs. */
  def encodeCursor(position: Position): String =
    Base64.urlEncode(s"${position.at}|${position.id}".getBytes(StandardCharsets.UTF_8))

  def decodeCursor(cursor: String): Option[Position] =
    Try(Base64.urlDecode(cursor)).toOption.flatMap: bytes =>
      String(bytes, StandardCharsets.UTF_8).split('|') match
        case Array(at, id) =>
          for
            parsedAt <- Try(Instant.parse(at)).toOption
            parsedId <- Try(UUID.fromString(id)).toOption
          yield Position(parsedAt, parsedId)
        case _ => None

  val live: ZLayer[AuditReadRepository & AuditEvents.Settings, Nothing, AuditReportService] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(repository: AuditReadRepository, settings: AuditEvents.Settings) extends AuditReportService:

    /** The window asked for: `from` defaults to the start of the retained window and `to` to now. Outside the retained window the
      * rows are partly or wholly gone, so the answer would look complete and not be: refused rather than clipped. */
    private def window(from: Option[Instant], to: Option[Instant]): IO[AuditReportError, Window] =
      Clock.instant.flatMap: now =>
        val retentionFrom = now.minusSeconds(settings.retention.toSeconds)
        val start = from.getOrElse(retentionFrom)
        val end = to.getOrElse(now)
        if end.isBefore(start) then ZIO.fail(AuditReportError.WindowReversed)
        else if start.isBefore(retentionFrom) || end.isAfter(now.plusSeconds(ClockSkew.toSeconds)) then
          ZIO.fail(AuditReportError.WindowOutsideRetention)
        else ZIO.succeed(Window(start, end, retentionFrom))

    override def events(
        userId: UUID,
        from: Option[Instant],
        to: Option[Instant],
        cursor: Option[String],
        limit: Option[Int],
    ): IO[Throwable | AuditReportError, EventsPage] =
      for
        window <- window(from, to)
        size = limit.getOrElse(DefaultLimit)
        _ <- ZIO.fail(AuditReportError.BadLimit).unless(size >= 1 && size <= MaxLimit)
        after <- ZIO.foreach(cursor)(value => ZIO.fromOption(decodeCursor(value)).orElseFail(AuditReportError.BadCursor))
        // One more than a page: whether there is a next page is whether it came back.
        found <- repository.userEvents(userId, window.from, window.to, after, size + 1)
        page = found.take(size)
      yield EventsPage(
        events = page,
        nextCursor = Option.when(found.size > size)(page.lastOption.map(last => encodeCursor(Position(last.at, last.id)))).flatten,
        retentionFrom = window.retentionFrom,
      )

    override def summary(userId: UUID, from: Option[Instant], to: Option[Instant]): IO[Throwable | AuditReportError, SummaryReport] =
      for
        window <- window(from, to)
        found <- repository.userSummary(userId, window.from, window.to)
      yield SummaryReport(
        window = window,
        byKind = found.byKind,
        byClient = found.byClient,
        ips = found.ips,
        userAgents = found.userAgents,
        sessions = found.sessions,
        factors = found.factors,
        high = found.high,
      )
