package versola.central.audit

import versola.central.CentralConfig
import versola.central.audit.AuditReportService.*
import versola.central.users.{AuthClient, UserId}
import versola.util.Base64Url
import versola.util.audit.AuditEvent
import zio.*
import zio.json.*
import zio.json.ast.Json

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
      cursor: Option[String],
      limit: Option[Int],
  ): IO[AuditReportError | Throwable, UserAuditReport]

object AuditReportService:
  val DefaultLimit = 100
  val MaxLimit = 500

  /** Rows of central's one page of auth's can bring with it. An administrator does not act on one user that often within a page of
    * their sign-ins; the bound keeps a page small whatever is in the table. */
  private[audit] val MaxCentralRowsPerPage = 500

  /** Rows of the summary's `adminActions`. */
  private[audit] val MaxAdminActions = 100

  /** Postgres keeps microseconds: an exclusive upper bound that includes `to` itself. */
  private val Micro = 1

  /** Where the previous page ended. `until` is where central's rows resume (exclusive); `auth` is auth's own cursor; `key` is the
    * position in central's rows when they are paged alone (`centralOnly`). */
  private[audit] case class Cursor(until: Instant, auth: Option[String], key: Option[Key], centralOnly: Boolean) derives JsonCodec
  private[audit] case class Key(at: Instant, id: UUID) derives JsonCodec

  private[audit] def encode(cursor: Cursor): String = Base64Url.encode(cursor.toJson.getBytes("UTF-8")).toString

  private[audit] def decode(raw: String): Either[String, Cursor] =
    scala.util.Try(Base64Url.decodeStr(raw)).toOption.toRight("the cursor is not valid").flatMap(_.fromJson[Cursor].left.map(_ =>
      "the cursor is not valid",
    ))

  val live: ZLayer[CentralAuditRepository & AuthClient & CentralConfig, Nothing, AuditReportService] =
    ZLayer.fromFunction(Impl(_, _, _))

  private[audit] class Impl(repository: CentralAuditRepository, authClient: AuthClient, config: CentralConfig) extends AuditReportService:

    override def userReport(
        userId: UserId,
        from: Option[Instant],
        to: Option[Instant],
        cursor: Option[String],
        limit: Option[Int],
    ): IO[AuditReportError | Throwable, UserAuditReport] =
      for
        now <- Clock.instant
        pageSize <- limit match
          case None => ZIO.succeed(DefaultLimit)
          case Some(n) if n >= 1 && n <= MaxLimit => ZIO.succeed(n)
          case Some(_) => ZIO.fail(AuditReportError.Invalid(s"limit is 1 to $MaxLimit"))
        position <- ZIO.foreach(cursor)(raw => ZIO.fromEither(decode(raw)).mapError(AuditReportError.Invalid(_)))
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
        if first then answered(authClient.getUserAuditSummary(userId, authFrom, authTo)) else ZIO.none
      (eventsCall <&> summaryCall).flatMap: (events, authSummary) =>
        events match
          case None =>
            // Auth did not answer: the report is central's rows alone, from where this page started.
            centralOnly(userId, lower, upper, until, None, pageSize, retentionFrom, authSummary, first)
          case Some(page) =>
            // Where this page of auth's ends is where central's rows stop: all of them down to it are on this page, and the next
            // page takes them from there. On the last page that is the start of the window.
            val low = page.nextCursor.flatMap(_ => page.events.lastOption.map(_.at)).getOrElse(lower)
            for
              mine <- repository.userEvents(userId, low, until, None, MaxCentralRowsPerPage)
              actions <- if first then adminActions(userId, lower, upper).asSome else ZIO.none
            yield UserAuditReport(
              userId = userId,
              window = ReportWindow(from = lower, to = upper, retentionFrom = laterOf(retentionFrom, page.retentionFrom)),
              summary = actions.map(withActions(authSummary, _)),
              events = ordered(page.events ++ mine),
              nextCursor = page.nextCursor.map(next => encode(Cursor(until = low, auth = Some(next), key = None, centralOnly = false))),
              partial = Nil,
            )

    /** Central's rows alone, in pages of their own: auth did not answer, or an earlier page of this report was served without it. */
    private def centralOnly(
        userId: UserId,
        lower: Instant,
        upper: Instant,
        until: Instant,
        key: Option[Key],
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
        summary = actions.map(withActions(authSummary, _)),
        events = page,
        nextCursor = Option.when(rows.size > pageSize)(page.last).map(last =>
          encode(Cursor(until = until, auth = None, key = Some(Key(last.at, last.id)), centralOnly = true)),
        ),
        partial = List("auth"),
      )

    /** What auth answered, or nothing when it did not (the report goes on without it); its refusal of the request is the caller's. */
    private def answered[A](call: IO[AuthAuditError | Throwable, A]): IO[AuditReportError, Option[A]] =
      call.map(Some(_)).catchAll:
        case AuthAuditError.Invalid(message) => ZIO.fail(AuditReportError.Invalid(message))
        case AuthAuditError.Unavailable(reason) => ZIO.logWarning(s"The audit report is served without auth's part: $reason").as(None)
        // Auth answered with something central cannot read: no better than not answering, and worth a stack trace.
        case cause: Throwable =>
          ZIO.logWarningCause("The audit report is served without auth's part: its answer could not be read", Cause.fail(cause)).as(None)

    private def adminActions(userId: UserId, from: Instant, to: Instant): Task[List[AdminAction]] =
      repository.userEvents(
        userId,
        from,
        to.plus(Micro, ChronoUnit.MICROS),
        None,
        MaxAdminActions,
      ).map(_.filter(_.kind.value.startsWith("admin.")).map: event =>
        AdminAction(
          at = event.at,
          actorId = event.actorId,
          kind = event.kind.value,
          targetType = event.targetType,
          targetId = event.targetId,
          changed = event.details.fields.collectFirst { case ("changed", Json.Arr(names)) =>
            names.collect { case Json.Str(name) => name }.toList
          }.getOrElse(Nil),
        ))

    /** Auth's summary, as it answered it, with the administrators' actions beside it. */
    private def withActions(authSummary: Option[Json.Obj], actions: List[AdminAction]): Json.Obj =
      val own = actions.toJsonAST.toOption.getOrElse(Json.Arr())
      Json.Obj(Chunk.fromIterable(authSummary.fold(Chunk.empty[(
          String,
          Json,
      )])(_.fields.filterNot(_._1 == "adminActions")) :+ ("adminActions" -> own)))

    private def laterOf(a: Instant, b: Instant): Instant = if a.isAfter(b) then a else b

    /** Newest first; rows of one instant by id, descending, as both stores order them. */
    private def ordered(events: List[AuditEvent]): List[AuditEvent] =
      events.sortBy(e => (e.at, e.id.toString))(using Ordering[(Instant, String)].reverse)
