package versola.central.audit

import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.users.{AuthClient, UserId}
import versola.util.PageLimit
import versola.util.audit.*
import zio.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object AuditReportServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val now = Instant.parse("2026-11-04T12:00:00Z")
  private val authRetentionFrom = Instant.parse("2026-10-28T00:00:00Z")
  private def at(time: String) = Instant.parse(s"2026-11-03T$time:00Z")

  private def event(n: Int, source: AuditSource, time: Instant, kind: String) = AuditEvent(
    id = UUID.fromString(f"00000000-0000-7000-8000-$n%012d"),
    at = time,
    tenantId = None,
    source = source,
    nodeId = "n",
    kind = AuditKind(kind),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = if source == AuditSource("auth") then ActorType.user else CentralAudit.Actors.Admin,
    actorId = Some("actor"),
    userId = Some(userId),
    clientId = None,
    sessionId = None,
    ip = None,
    userAgent = None,
    traceId = None,
    targetType = Some("user"),
    targetId = Some(userId.toString),
    details = if source == CentralAudit.Source then Json.Obj("changed" -> Json.Arr(Json.Str("claims"))) else Json.Obj(),
  )

  private val login = event(1, AuditSource("auth"), at("10:00"), "login.completed")
  private val token = event(2, AuditSource("auth"), at("09:00"), "token.issued")
  private val reset = event(3, CentralAudit.Source, at("09:30"), "admin.user-password.action:reset")
  private val claims = event(4, CentralAudit.Source, at("09:45"), "admin.user-claims.update")

  private def authPage(events: List[AuditEvent], next: Option[String]) =
    UserAuditEventsResponse(events = events.map(ReportedEvent(_, None)), nextCursor = next, retentionFrom = authRetentionFrom)

  private val authSummary = Json.Obj("byKind" -> Json.Arr(), "sessions" -> Json.Obj("created" -> Json.Num(2), "ended" -> Json.Num(1)))

  private class Env:
    val repository = stub[CentralAuditRepository]
    val authClient = stub[AuthClient]
    val places = Unsafe.unsafe(Semaphore.unsafe.make(AuditReportService.MaxConcurrentReports)(using _))
    val service = AuditReportService.Impl(repository, authClient, TestCentralConfig.config, places)

  /** The two logs as lists, with fakes that answer as the real ones do: newest first by `(at, id)`, `to` inclusive, a limit, and a next
    * cursor for auth when there is more. */
  private class Logs(auth: List[AuditEvent], central: List[AuditEvent]):
    val env = Env()
    private val failing = Unsafe.unsafe(Ref.unsafe.make(Option.empty[Int])(using _))
    private val authCalls = Unsafe.unsafe(Ref.unsafe.make(0)(using _))

    private def desc(events: List[AuditEvent]) = events.sortBy(e => (e.at, e.id.toString))(using Ordering[(Instant, String)].reverse)

    /** Both logs together, newest first. */
    val all: List[AuditEvent] = desc(auth ++ central)

    /** Auth answers its first `calls` calls and is down after that. */
    def authDownAfter(calls: Int): UIO[Unit] = failing.set(Some(calls))

    val install: UIO[Unit] =
      for
        _ <- env.authClient.getUserAuditEvents.returnsZIO: args =>
          val query = args._2
          for
            n <- authCalls.updateAndGet(_ + 1)
            down <- failing.get
            _ <- ZIO.fail(AuthAuditError.Unavailable("down")).when(down.exists(n > _))
          yield
            val inside = desc(auth.filter(e => query.from.forall(f => !e.at.isBefore(f)) && query.to.forall(t => !e.at.isAfter(t))))
            authPage(inside.take(query.limit), Option.when(inside.size > query.limit)("more"))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.returnsZIO: args =>
          val (_, from, until, before, limit) = args
          ZIO.succeed(
            desc(
              central.filter(e =>
                !e.at.isBefore(from) && e.at.isBefore(until) &&
                  before.forall(k => e.at.isBefore(k.at) || (e.at == k.at && e.id.toString < k.id.toString)),
              ),
            ).take(limit),
          )
        _ <- env.repository.adminActions.succeedsWith(Nil)
      yield ()

    /** The pages of a walk that follows `nextCursor` to the end, with a bound so that a cursor that goes nowhere fails the spec. */
    def walk(pageSize: Int): ZIO[Any, Throwable, List[UserAuditReport]] =
      def next(cursor: Option[String], pages: List[UserAuditReport]): ZIO[Any, Throwable, List[UserAuditReport]] =
        env.service.userReport(userId, None, None, cursor.flatMap(AuditCursor.decode(_).toOption), Some(PageLimit(pageSize))).mapError(e =>
          RuntimeException(e.toString),
        ).flatMap: page =>
          if pages.size > 200 then ZIO.fail(RuntimeException("the walk did not end"))
          else page.nextCursor.fold(ZIO.succeed(pages :+ page))(c => next(Some(c), pages :+ page))
      authCalls.set(0) *> next(None, Nil)

  private val clock = TestClock.setTime(now)

  def spec = suite("AuditReportService")(
    test("the first page holds auth's events and central's rows newest first, with auth's summary and the administrators' actions") {
      val logs = Logs(auth = List(login, token), central = List(claims, reset))
      for
        _ <- clock
        _ <- logs.install
        report <- logs.env.service.userReport(userId, None, None, None, Some(PageLimit(10)))
      yield assertTrue(
        report.events.map(_.event.id) == List(login, claims, reset, token).map(_.id),
        report.partial.isEmpty,
        report.nextCursor.isEmpty,
        report.summary.exists(s => s.fields.map(_._1).contains("sessions") && s.fields.map(_._1).contains("adminActions")),
        // auth is asked for a few more than the page, central for one more, and neither is told a cursor
        logs.env.authClient.getUserAuditEvents.calls.head._2 == UserAuditEventsQuery(None, None, None, 10 + AuditReportService.AuthSlack),
        logs.env.repository.userEvents.calls.head._4.isEmpty,
        logs.env.repository.userEvents.calls.head._5 == 11,
      )
    },
    test("a page is never longer than the page size asked for, whatever the two logs hold") {
      val logs = Logs(
        auth = (1 to 30).toList.map(n => event(n, AuditSource("auth"), at("10:00").minusSeconds(n * 2L), "token.issued")),
        central = (1 to 30).toList.map(n => event(100 + n, CentralAudit.Source, at("10:00").minusSeconds(n * 2L + 1), "admin.user.update")),
      )
      for
        _ <- clock
        _ <- logs.install
        pages <- logs.walk(7)
      yield assertTrue(pages.forall(_.events.size <= 7), pages.init.forall(_.events.size == 7))
    },
    test("a window the caller did not name is auth's whole retained window: auth is not told one, and central's own retention bounds its rows") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(Nil, None))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(Nil)
        _ <- env.repository.adminActions.succeedsWith(Nil)
        _ <- env.service.userReport(userId, None, None, None, None)
      yield assertTrue(
        env.authClient.getUserAuditEvents.calls.head._2 == UserAuditEventsQuery(None, None, None, PageLimit.Default + AuditReportService.AuthSlack),
        env.authClient.getUserAuditSummary.calls.head._2.isEmpty,
        env.repository.userEvents.calls.head._2 == now.minus(7, ChronoUnit.DAYS),
      )
    },
    test("a window that starts before the retention is clipped to it, and one that ends in the future ends now") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(Nil, None))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(Nil)
        _ <- env.repository.adminActions.succeedsWith(Nil)
        report <- env.service.userReport(userId, Some(Instant.parse("2020-01-01T00:00:00Z")), Some(now.plusSeconds(86400)), None, None)
      yield assertTrue(
        report.window.from == now.minus(7, ChronoUnit.DAYS),
        report.window.to == now,
        env.authClient.getUserAuditEvents.calls.head._2.from.contains(now.minus(7, ChronoUnit.DAYS)),
        env.authClient.getUserAuditEvents.calls.head._2.to.contains(now),
      )
    },
    test("an answer of auth's that cannot be read leaves its part out like no answer at all") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.failsWith(RuntimeException("Auth response decode failed"))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(List(reset))
        _ <- env.repository.adminActions.succeedsWith(List(reset))
        report <- env.service.userReport(userId, None, None, None, None)
      yield assertTrue(report.partial == List("auth"), report.events.map(_.event) == List(reset))
    },
    test("more than a page of central rows inside one span of auth's are all reached by following the cursor, in order, none twice") {
      val logs = Logs(
        auth = List(login, token, event(5, AuditSource("auth"), at("08:00"), "session.ended")),
        central = (0 to 24).toList.map(n => event(100 + n, CentralAudit.Source, at("09:59").minusSeconds(n * 60L), "admin.user.update")),
      )
      for
        _ <- clock
        _ <- logs.install
        pages <- logs.walk(10)
        walked = pages.flatMap(_.events.map(_.event))
      yield assertTrue(
        walked.map(_.id) == logs.all.map(_.id),
        walked.map(_.id).distinct.size == walked.size,
        pages.size == 3,
        pages.last.nextCursor.isEmpty,
        pages.dropRight(1).forall(_.nextCursor.isDefined),
      )
    },
    test("events of the two logs interleaved across the boundary of a page come out in one order, none lost and none twice") {
      val auth = (0 to 11).toList.map(n => event(1 + n, AuditSource("auth"), at("10:00").minusSeconds(n * 90L), "token.issued"))
      val central = (0 to 11).toList.map(n => event(100 + n, CentralAudit.Source, at("10:00").minusSeconds(n * 90L + 45), "admin.user.update"))
      val logs = Logs(auth, central)
      for
        _ <- clock
        _ <- logs.install
        pages <- logs.walk(5)
        walked = pages.flatMap(_.events.map(_.event))
      yield assertTrue(
        walked.map(_.id) == logs.all.map(_.id),
        walked.map(_.id).distinct.size == walked.size,
        pages.map(_.events.size) == List(5, 5, 5, 5, 4),
      )
    },
    test("events at the same microsecond are told apart by their ids across the boundary of a page") {
      val same = at("09:00")
      val auth =
        List(1, 3, 5, 7).map(n => event(n, AuditSource("auth"), same, "login.step")) :+ event(9, AuditSource("auth"), at("08:00"), "token.issued")
      val central = List(2, 4, 6, 8).map(n => event(100 + n, CentralAudit.Source, same, "admin.user.update"))
      val logs = Logs(auth, central)
      for
        _ <- clock
        _ <- logs.install
        pages <- ZIO.foreach(List(1, 2, 3, 4))(size => logs.walk(size))
        walked = pages.map(_.flatMap(_.events.map(_.event.id)))
      yield assertTrue(walked.forall(_ == logs.all.map(_.id)), walked.forall(ids => ids.distinct.size == ids.size))
    },
    test("a page that is exactly full with nothing left has no cursor, and one with a single event more has") {
      val ten = (0 to 9).toList.map(n => event(1 + n, AuditSource("auth"), at("10:00").minusSeconds(n * 10L), "token.issued"))
      val eleven = ten :+ event(50, CentralAudit.Source, at("09:00"), "admin.user.update")
      for
        _ <- clock
        full = Logs(ten, Nil)
        _ <- full.install
        exact <- full.env.service.userReport(userId, None, None, None, Some(PageLimit(10)))
        more = Logs(eleven.filter(_.source == AuditSource("auth")), eleven.filter(_.source == CentralAudit.Source))
        _ <- more.install
        over <- more.env.service.userReport(userId, None, None, None, Some(PageLimit(10)))
        rest <- more.env.service.userReport(userId, None, None, over.nextCursor.flatMap(AuditCursor.decode(_).toOption), Some(PageLimit(10)))
      yield assertTrue(
        exact.events.size == 10,
        exact.nextCursor.isEmpty,
        over.events.size == 10,
        over.nextCursor.isDefined,
        rest.events.map(_.event.id) == List(eleven.last.id),
        rest.nextCursor.isEmpty,
        rest.summary.isEmpty,
      )
    },
    test("a page of the largest size, where auth cannot be asked for more than the page, still ends at the right event") {
      val auth = (0 to 599).toList.map(n => event(1 + n, AuditSource("auth"), at("10:00").minusSeconds(n.toLong), "token.issued"))
      val central = (0 to 4).toList.map(n => event(1000 + n, CentralAudit.Source, at("10:00").minusSeconds(n * 100L + 50), "admin.user.update"))
      val logs = Logs(auth, central)
      for
        _ <- clock
        _ <- logs.install
        pages <- logs.walk(PageLimit.Max)
        walked = pages.flatMap(_.events.map(_.event.id))
      yield assertTrue(walked == logs.all.map(_.id), walked.distinct.size == walked.size, pages.forall(_.events.size <= PageLimit.Max))
    },
    test("when auth stops answering in the middle of a walk the rest of it is central's rows alone, flagged, without repeating or losing any") {
      val auth = (0 to 9).toList.map(n => event(1 + n, AuditSource("auth"), at("10:00").minusSeconds(n * 60L), "token.issued"))
      val central = (0 to 14).toList.map(n => event(100 + n, CentralAudit.Source, at("10:00").minusSeconds(n * 60L + 30), "admin.user.update"))
      val logs = Logs(auth, central)
      for
        _ <- clock
        _ <- logs.install
        _ <- logs.authDownAfter(1)
        pages <- logs.walk(6)
        first = pages.head
        rest = pages.tail
        restIds = rest.flatMap(_.events.map(_.event.id))
        firstKey = first.events.last.event
        centralAfterFirst =
          central.filter(e => e.at.isBefore(firstKey.at) || (e.at == firstKey.at && e.id.toString < firstKey.id.toString)).sortBy(_.at)(using
            Ordering[Instant].reverse,
          )
      yield assertTrue(
        first.partial.isEmpty,
        rest.nonEmpty,
        rest.forall(_.partial == List("auth")),
        rest.flatMap(_.events).forall(_.event.source == CentralAudit.Source),
        restIds == centralAfterFirst.map(_.id),
        restIds.distinct.size == restIds.size,
        rest.forall(_.summary.isEmpty),
      )
    },
    test("the administrators' changes in the summary are the repository's, cut at their bound, and the report says when they were more") {
      def actions(n: Int) = (1 to n).toList.map(i => event(300 + i, CentralAudit.Source, at("09:30").minusSeconds(i), "admin.user.update"))
      def report(changes: List[AuditEvent]) =
        val env = Env()
        for
          _ <- clock
          _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(Nil, None))
          _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
          _ <- env.repository.userEvents.succeedsWith(Nil)
          _ <- env.repository.adminActions.succeedsWith(changes)
          report <- env.service.userReport(userId, None, None, None, None)
        yield (report, env.repository.adminActions.calls.head._4)
      for
        over <- report(actions(AuditReportService.MaxAdminActions + 1))
        at <- report(actions(AuditReportService.MaxAdminActions))
        listed = over._1.summary.flatMap(_.get("adminActions")).collect { case Json.Arr(items) => items.size }
      yield assertTrue(
        over._1.truncated,
        listed.contains(AuditReportService.MaxAdminActions),
        over._2 == AuditReportService.MaxAdminActions + 1,
        !at._1.truncated,
      )
    },
    test("a report served without auth is counted by why: an auth without the endpoint is not an outage") {
      def partials(reason: String) =
        Metric.counter("audit_report_partial_total").tagged(Set(MetricLabel("reason", reason))).value.map(_.count)
      def without(error: AuthAuditError) =
        val env = Env()
        for
          _ <- clock
          _ <- env.authClient.getUserAuditEvents.failsWith(error)
          _ <- env.authClient.getUserAuditSummary.failsWith(error)
          _ <- env.repository.userEvents.succeedsWith(Nil)
          _ <- env.repository.adminActions.succeedsWith(Nil)
          report <- env.service.userReport(userId, None, None, None, None)
        yield report
      for
        missingBefore <- partials("endpoint_missing")
        downBefore <- partials("unavailable")
        missing <- without(AuthAuditError.EndpointMissing)
        missingAfter <- partials("endpoint_missing")
        down <- without(AuthAuditError.Unavailable("auth answered 500"))
        downAfter <- partials("unavailable")
      yield assertTrue(
        missing.partial == List("auth"),
        down.partial == List("auth"),
        // once per report, not once per call to auth
        missingAfter == missingBefore + 1,
        downAfter == downBefore + 1,
      )
    },
    test("the country auth gave an address is passed on in the event and in the summary, and central's own rows have none") {
      val env = Env()
      val summary = Json.Obj(
        "ips" -> Json.Arr(Json.Obj("ip" -> Json.Str("203.0.113.7"), "count" -> Json.Num(2), "country" -> Json.Str("KZ"))),
        "countries" -> Json.Arr(Json.Obj("country" -> Json.Str("KZ"), "count" -> Json.Num(2))),
      )
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(
          UserAuditEventsResponse(
            events = List(ReportedEvent(login, Some("KZ")), ReportedEvent(token, None)),
            nextCursor = None,
            retentionFrom = authRetentionFrom,
          ),
        )
        _ <- env.authClient.getUserAuditSummary.succeedsWith(summary)
        _ <- env.repository.userEvents.succeedsWith(List(reset))
        _ <- env.repository.adminActions.succeedsWith(List(reset))
        report <- env.service.userReport(userId, None, None, None, None)
        json <- ZIO.fromEither(report.toJsonAST).mapError(RuntimeException(_))
        events = json.asObject.flatMap(_.get("events")).collect { case Json.Arr(items) =>
          items.toList.collect { case obj: Json.Obj => obj }
        }.getOrElse(Nil)
        countryOf = (id: UUID) => events.find(_.get("id").contains(Json.Str(id.toString))).flatMap(_.get("country"))
      yield assertTrue(
        countryOf(login.id).contains(Json.Str("KZ")),
        // an event without one has no member, and so has a row of central's
        countryOf(token.id).isEmpty,
        countryOf(reset.id).isEmpty,
        report.summary.flatMap(_.get("countries")).isDefined,
        report.summary.flatMap(_.get("ips")).exists(_.toJson.contains("\"country\":\"KZ\"")),
      )
    },
    test("only four reports run at a time: a fifth waits for a place and is refused as busy when none frees in time") {
      val env = Env()
      for
        _ <- clock
        gate <- Promise.make[Nothing, Unit]
        // auth does not answer until the gate opens, so every report that got a place holds it
        _ <- env.authClient.getUserAuditEvents.returnsZIO(_ => gate.await.as(authPage(Nil, None)))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(Nil)
        _ <- env.repository.adminActions.succeedsWith(Nil)
        running <- ZIO.foreach(1 to AuditReportService.MaxConcurrentReports)(_ => env.service.userReport(userId, None, None, None, None).fork)
        _ <- ZIO.yieldNow.repeatN(20)
        fifth <- env.service.userReport(userId, None, None, None, None).either.fork
        _ <- ZIO.yieldNow.repeatN(20)
        _ <- TestClock.adjust(AuditReportService.WaitForPlace + 1.second)
        refused <- fifth.join
        _ <- gate.succeed(())
        served <- ZIO.foreach(running)(_.join)
        // the places are free again
        again <- env.service.userReport(userId, None, None, None, None).either
      yield assertTrue(
        refused == Left(AuditReportError.Busy),
        served.size == AuditReportService.MaxConcurrentReports,
        again.isRight,
      )
    },
    test("auth's refusal of the window is the caller's: a bad request, not a report without auth") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.failsWith(AuthAuditError.Invalid("from is before the retention"))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(Nil)
        _ <- env.repository.adminActions.succeedsWith(Nil)
        refused <- env.service.userReport(userId, None, None, None, None).either
      yield assertTrue(refused == Left(AuditReportError.Invalid("from is before the retention")))
    },
    test("a window that ends before it starts is refused, and nothing is asked of anyone") {
      val env = Env()
      for
        _ <- clock
        backwards <- env.service.userReport(userId, Some(at("11:00")), Some(at("10:00")), None, None).either
      yield assertTrue(
        backwards == Left(AuditReportError.Invalid("to is before from")),
        env.authClient.getUserAuditEvents.calls.isEmpty,
        env.repository.userEvents.calls.isEmpty,
      )
    },
  )
