package versola.central.audit

import versola.central.CentralConfig
import versola.central.audit.AuditReportService.*
import versola.central.users.{AuthClient, UserId}
import versola.util.PageLimit
import versola.util.audit.AuditEvent
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.metrics.{Metric, MetricLabel}

import java.time.Instant
import java.time.temporal.ChronoUnit

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

  /** Rows of central's one page of auth's can bring with it. An administrator does not act on one user that often within a page of
    * their sign-ins; the bound keeps a page small whatever is in the table. */
  private[audit] val MaxCentralRowsPerPage = 500

  /** Rows of the summary's `adminActions`. */
  private[audit] val MaxAdminActions = 100

  /** Postgres keeps microseconds: an exclusive upper bound that includes `to` itself. */
  private val Micro = 1

  private val partialReports = Metric.counter("audit_report_partial_total")

  /** Reports that run at the same time: each reads the table and asks auth, so a few at once are served and the rest wait. */
  val MaxConcurrentReports = 4

  /** How long a report waits for one of the places before it is refused as busy. */
  val WaitForPlace: Duration = 5.seconds

  val live: ZLayer[CentralAuditRepository & AuthClient & CentralConfig, Nothing, AuditReportService] =
    ZLayer.fromZIO(Semaphore.make(MaxConcurrentReports)) ++ ZLayer.environment[CentralAuditRepository & AuthClient & CentralConfig] >>>
      ZLayer.fromFunction(Impl(_, _, _, _))

  private[audit] class Impl(repository: CentralAuditRepository, authClient: AuthClient, config: CentralConfig, places: Semaphore)
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
        retentionFrom = now.minusMillis(config.audit.retention.toMillis).truncatedTo(ChronoUnit.MICROS)
        upper = to.fold(now)(t => if t.isAfter(now) then now else t)
        lower = from.fold(retentionFrom)(f => if f.isBefore(retentionFrom) then retentionFrom else f)
        _ <- ZIO.fail(AuditReportError.Invalid("to is before from")).when(upper.isBefore(lower))
        until = position.fold(upper.plus(Micro, ChronoUnit.MICROS))(_.until)
        // Auth is asked for the window the caller named, or for its whole retained window when it named none: its own limit of
        // how far back it goes is not central's to guess.
        authFrom = from.map(_ => lower)
        authTo = to.map(_ => upper)
        report <- position match
          case Some(p) if p.centralOnly => centralOnly(userId, lower, upper, until, p.key, pageSize, retentionFrom, authSummary = None, first = false)
          case _ => merged(userId, lower, upper, until, authFrom, authTo, position.flatMap(_.auth), pageSize, retentionFrom, first = position.isEmpty)
      yield report

    /** Auth's page and central's rows of the span it covers. */
    private def merged(
        userId: UserId,
        lower: Instant,
        upper: Instant,
        until: Instant,
        authFrom: Option[Instant],
        authTo: Option[Instant],
        authCursor: Option[String],
        pageSize: Int,
        retentionFrom: Instant,
        first: Boolean,
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      val eventsCall = answered(authClient.getUserAuditEvents(userId, UserAuditEventsQuery(authFrom, authTo, authCursor, pageSize)))
      val summaryCall: IO[AuditReportError, Option[Json.Obj]] =
        if first then answered(authClient.getUserAuditSummary(userId, authFrom, authTo)).map(_.toOption) else ZIO.none
      (eventsCall <&> summaryCall).flatMap: (events, authSummary) =>
        events match
          case Left(reason) =>
            // Auth did not answer: the report is central's rows alone, from where this page started.
            partialReports.tagged(Set(MetricLabel("reason", reason))).increment *>
              centralOnly(userId, lower, upper, until, None, pageSize, retentionFrom, authSummary, first)
          case Right(page) =>
            // Where this page of auth's ends is where central's rows stop: all of them down to it are on this page, and the next
            // page takes them from there. On the last page that is the start of the window.
            val low = page.nextCursor.flatMap(_ => page.events.lastOption.map(_.event.at)).getOrElse(lower)
            for
              // one more than the bound, to know whether the bound cut anything
              found <- repository.userEvents(userId, low, until, None, MaxCentralRowsPerPage + 1)
              mine = found.take(MaxCentralRowsPerPage)
              actions <- if first then adminActions(userId, lower, upper).asSome else ZIO.none
            yield UserAuditReport(
              userId = userId,
              window = ReportWindow(from = lower, to = upper, retentionFrom = laterOf(retentionFrom, page.retentionFrom)),
              summary = actions.map(done => withActions(authSummary, done._1)),
              events = ordered(page.events ++ mine.map(ReportedEvent(_, None))),
              nextCursor =
                page.nextCursor.map(next => AuditCursor.encode(AuditCursor(until = low, auth = Some(next), key = None, centralOnly = false))),
              partial = Nil,
              truncated = found.size > MaxCentralRowsPerPage || actions.exists(_._2),
            )

    /** Central's rows alone, in pages of their own: auth did not answer, or an earlier page of this report was served without it. */
    private def centralOnly(
        userId: UserId,
        lower: Instant,
        upper: Instant,
        until: Instant,
        key: Option[AuditCursor.Key],
        pageSize: Int,
        retentionFrom: Instant,
        authSummary: Option[Json.Obj],
        first: Boolean,
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      for
        rows <- repository.userEvents(userId, lower, until, key.map(k => CentralAuditRepository.Key(k.at, k.id)), pageSize + 1)
        page = rows.take(pageSize)
        actions <- if first then adminActions(userId, lower, upper).asSome else ZIO.none
      yield UserAuditReport(
        userId = userId,
        window = ReportWindow(from = lower, to = upper, retentionFrom = retentionFrom),
        summary = actions.map(done => withActions(authSummary, done._1)),
        events = page.map(ReportedEvent(_, None)),
        nextCursor = Option.when(rows.size > pageSize)(page.last).map(last =>
          AuditCursor.encode(AuditCursor(until = until, auth = None, key = Some(AuditCursor.Key(last.at, last.id)), centralOnly = true)),
        ),
        partial = List("auth"),
        truncated = actions.exists(_._2),
      )

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
