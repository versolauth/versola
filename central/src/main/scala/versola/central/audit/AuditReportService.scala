package versola.central.audit

import versola.central.audit.AuditReportService.*
import versola.central.users.{AuthClient, UserId}
import versola.util.PageLimit
import versola.util.audit.{AuditEvent, AuditEvents}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.metrics.{Metric, MetricLabel}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The per-user report: auth's per-user events merged with central's own rows about the user (what administrators did to them).
  *
  * A page is made of a page of auth's (its own cursor, newest first) and every row of central's that falls in the time span that
  * page covers, so the merged list is in order and no row of either is lost between pages. When auth does not answer, the page is
  * central's rows alone, in pages of their own, and the report says `partial: ["auth"]` rather than failing: the administrator's
  * own trail is still worth having.
  */
trait AuditReportService:
  def userReport(
      userId: UserId,
      from: Option[Instant],
      to: Option[Instant],
      cursor: Option[AuditCursor],
      limit: Option[PageLimit],
  ): IO[AuditReportError | Throwable, UserAuditReport]

object AuditReportService:

  /** Events auth is asked for beyond the page: those of the key's own instant that an earlier page already served come back with them. */
  private[audit] val AuthSlack = 5

  /** Rows of the summary's `adminActions`. */
  private[audit] val MaxAdminActions = 100

  /** Postgres keeps microseconds: an exclusive upper bound that includes `to` itself. */
  private val Micro = 1

  private val partialReports = Metric.counter("audit_report_partial_total")

  /** Reports that run at the same time: each reads the table and asks auth, so a few at once are served and the rest wait. */
  val MaxConcurrentReports = 4

  /** How long a report waits for one of the places before it is refused as busy. */
  val WaitForPlace: Duration = 5.seconds

  val live: ZLayer[CentralAuditRepository & AuthClient, Nothing, AuditReportService] =
    ZLayer.fromZIO(Semaphore.make(MaxConcurrentReports)) ++ ZLayer.environment[CentralAuditRepository & AuthClient] >>>
      ZLayer.fromFunction(Impl(_, _, _))

  private[audit] class Impl(repository: CentralAuditRepository, authClient: AuthClient, places: Semaphore)
    extends AuditReportService:

    override def userReport(
        userId: UserId,
        from: Option[Instant],
        to: Option[Instant],
        cursor: Option[AuditCursor],
        limit: Option[PageLimit],
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      ZIO.scoped:
        places.withPermitScoped.timeout(WaitForPlace).flatMap:
          case None => ZIO.fail(AuditReportError.Busy)
          case Some(_) => report(userId, from, to, cursor, limit)

    private def report(
        userId: UserId,
        from: Option[Instant],
        to: Option[Instant],
        position: Option[AuditCursor],
        limit: Option[PageLimit],
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      for
        now <- Clock.instant
        pageSize = limit.getOrElse(PageLimit.Default)
        retentionFrom = now.minusMillis(AuditEvents.Settings.standard.retention.toMillis).truncatedTo(ChronoUnit.MICROS)
        upper = to.fold(now)(t => if t.isAfter(now) then now else t)
        lower = from.fold(retentionFrom)(f => if f.isBefore(retentionFrom) then retentionFrom else f)
        _ <- ZIO.fail(AuditReportError.Invalid("to is before from")).when(upper.isBefore(lower))
        // Auth is asked for the window the caller named, or for its whole retained window when it named none: its own limit of
        // how far back it goes is not central's to guess.
        window = Window(lower = lower, upper = upper, authFrom = from.map(_ => lower), authTo = to.map(_ => upper), retentionFrom = retentionFrom)
        report <-
          if position.exists(_.centralOnly) then centralOnly(userId, window, position, pageSize, authSummary = None)
          else merged(userId, window, position, pageSize, asked = (pageSize + AuthSlack).min(PageLimit.Max))
      yield report

    /** The span of the report, and what auth is told of it. */
    private case class Window(lower: Instant, upper: Instant, authFrom: Option[Instant], authTo: Option[Instant], retentionFrom: Instant)

    /** A page of the report: the events of auth and the rows of central, newest first, `pageSize` of them in all. Both sources order by
      * `(at, id)` descending, and auth's `to` is inclusive, so the cursor is the key of the last event of the page: the next page asks
      * auth for what is at or before that instant and keeps what is strictly older, and reads central's rows strictly older. */
    private def merged(
        userId: UserId,
        window: Window,
        position: Option[AuditCursor],
        pageSize: Int,
        asked: Int,
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      val first = position.isEmpty
      val eventsCall = answered(
        authClient.getUserAuditEvents(
          userId,
          UserAuditEventsQuery(window.authFrom, position.map(_.at).orElse(window.authTo), None, asked),
        ),
      )
      val summaryCall: IO[AuditReportError, Option[Json.Obj]] =
        if first then answered(authClient.getUserAuditSummary(userId, window.authFrom, window.authTo)).map(_.toOption) else ZIO.none
      (eventsCall <&> summaryCall).flatMap: (events, authSummary) =>
        events match
          case Left(reason) =>
            // Auth did not answer: the rest of the report is central's rows alone.
            partialReports.tagged(Set(MetricLabel("reason", reason))).increment *>
              centralOnly(userId, window, position, pageSize, authSummary)
          case Right(page) =>
            (for
              // one more than the page, to know whether central has more
              found <- repository.userEvents(userId, window.lower, window.upper.plus(Micro, ChronoUnit.MICROS), position.map(keyOf), pageSize + 1)
              actions <- if first then adminActions(userId, window.lower, window.upper).asSome else ZIO.none
            yield
              val authOlder = position.fold(page.events)(p => page.events.filter(item => isOlder(item.event.at, item.event.id, p.at, p.id)))
              val authHasMore = page.nextCursor.isDefined
              // What auth did not send, because of its limit, is older than the last event it sent: past that key the merge cannot say what comes next.
              val horizon = Option.when(authHasMore)(page.events.lastOption).flatten.map(item => (item.event.at, item.event.id))
              val candidates = ordered(authOlder ++ found.map(ReportedEvent(_, None)))
                .filter(item => horizon.forall((at, id) => !isOlder(item.event.at, item.event.id, at, id)))
              val taken = candidates.take(pageSize)
              val more = candidates.size > pageSize || authHasMore || found.size > pageSize
              (taken, more, actions, page.retentionFrom)
            ).flatMap: (taken, more, actions, authRetentionFrom) =>
              // Auth has more, but everything it sent is at or before the cursor's instant and was served: a larger window of its events
              // gets past them. A page with no events and no cursor would end the walk with events left unread.
              if taken.isEmpty && more then
                if asked >= PageLimit.Max then
                  ZIO.fail(IllegalStateException("audit report: more events at one instant than a page of auth's events holds"))
                else merged(userId, window, position, pageSize, asked = (asked * 2).min(PageLimit.Max))
              else
                ZIO.succeed(
                  UserAuditReport(
                    userId = userId,
                    window = ReportWindow(from = window.lower, to = window.upper, retentionFrom = laterOf(window.retentionFrom, authRetentionFrom)),
                    summary = actions.map(done => withActions(authSummary, done._1)),
                    events = taken,
                    nextCursor =
                      Option.when(more)(taken.lastOption).flatten.map(last => AuditCursor.encode(cursorAfter(last.event, centralOnly = false))),
                    partial = Nil,
                    truncated = actions.exists(_._2),
                  ),
                )

    /** Central's rows alone, in pages of their own: auth did not answer, or an earlier page of this report was served without it (the
      * cursor says so, and the rest of the walk goes on without auth rather than with a hole in the middle). */
    private def centralOnly(
        userId: UserId,
        window: Window,
        position: Option[AuditCursor],
        pageSize: Int,
        authSummary: Option[Json.Obj],
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      for
        rows <- repository.userEvents(userId, window.lower, window.upper.plus(Micro, ChronoUnit.MICROS), position.map(keyOf), pageSize + 1)
        page = rows.take(pageSize)
        actions <- if position.isEmpty then adminActions(userId, window.lower, window.upper).asSome else ZIO.none
      yield UserAuditReport(
        userId = userId,
        window = ReportWindow(from = window.lower, to = window.upper, retentionFrom = window.retentionFrom),
        summary = actions.map(done => withActions(authSummary, done._1)),
        events = page.map(ReportedEvent(_, None)),
        nextCursor = Option.when(rows.size > pageSize)(page.last).map(last => AuditCursor.encode(cursorAfter(last, centralOnly = true))),
        partial = List("auth"),
        truncated = actions.exists(_._2),
      )

    private def keyOf(cursor: AuditCursor): CentralAuditRepository.Key = CentralAuditRepository.Key(cursor.at, cursor.id)

    private def cursorAfter(event: AuditEvent, centralOnly: Boolean): AuditCursor =
      AuditCursor(at = event.at, id = event.id, centralOnly = centralOnly)

    /** Whether the key `(at, id)` is strictly after `(than, thanId)` in the order of both logs: newest first, an id by its bytes. */
    private def isOlder(at: Instant, id: UUID, than: Instant, thanId: UUID): Boolean =
      at.isBefore(than) || (at == than && id.toString < thanId.toString)

    /** What auth answered, or why it did not (the report goes on without it, and says so); its refusal of the request is the caller's.
      * The reason is a closed set: `unavailable`, `endpoint_missing`, `unreadable`. */
    private def answered[A](call: IO[AuthAuditError | Throwable, A]): IO[AuditReportError, Either[String, A]] =
      call.map(Right(_)).catchAll:
        case AuthAuditError.Invalid(message) => ZIO.fail(AuditReportError.Invalid(message))
        case AuthAuditError.Unavailable(reason) =>
          ZIO.logWarning(s"The audit report is served without auth's part: $reason").as(Left("unavailable"))
        case AuthAuditError.EndpointMissing =>
          ZIO.logWarning("The audit report is served without auth's part: auth has no such endpoint").as(Left("endpoint_missing"))
        // Auth answered with something central cannot read: no better than not answering, and worth a stack trace.
        case cause: Throwable =>
          ZIO.logWarningCause(
            "The audit report is served without auth's part: its answer could not be read",
            Cause.fail(cause),
          ).as(Left("unreadable"))

    /** The administrators' changes to the user, and whether there were more than the bound. */
    private def adminActions(userId: UserId, from: Instant, to: Instant): Task[(List[AdminAction], Boolean)] =
      repository.adminActions(
        userId,
        from,
        to.plus(Micro, ChronoUnit.MICROS),
        MaxAdminActions + 1,
      ).map(found =>
        (
          found.take(MaxAdminActions).map: event =>
            AdminAction(
              at = event.at,
              actorId = event.actorId,
              kind = event.kind.value,
              targetType = event.targetType,
              targetId = event.targetId,
              changed = event.details.fields.collectFirst { case ("changed", Json.Arr(names)) =>
                names.collect { case Json.Str(name) => name }.toList
              }.getOrElse(Nil),
            ),
          found.size > MaxAdminActions,
        ),
      )

    /** Auth's summary, as it answered it, with the administrators' actions beside it. */
    private def withActions(authSummary: Option[Json.Obj], actions: List[AdminAction]): Json.Obj =
      val own = actions.toJsonAST.toOption.getOrElse(Json.Arr())
      Json.Obj(Chunk.fromIterable(authSummary.fold(Chunk.empty[(
          String,
          Json,
      )])(_.fields.filterNot(_._1 == "adminActions")) :+ ("adminActions" -> own)))

    private def laterOf(a: Instant, b: Instant): Instant = if a.isAfter(b) then a else b

    /** Newest first; rows of one instant by id, descending, as both stores order them. */
    private def ordered(events: List[ReportedEvent]): List[ReportedEvent] =
      events.sortBy(e => (e.event.at, e.event.id.toString))(using Ordering[(Instant, String)].reverse)
