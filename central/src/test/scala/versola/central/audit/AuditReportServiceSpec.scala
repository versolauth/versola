package versola.central.audit

import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.users.{AuthClient, UserId}
import versola.util.audit.*
import zio.*
import zio.json.ast.Json
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
    actorType = if source == AuditSource.auth then ActorType.user else ActorType.admin,
    actorId = Some("actor"),
    userId = Some(userId),
    clientId = None,
    sessionId = None,
    ip = None,
    userAgent = None,
    traceId = None,
    targetType = Some("user"),
    targetId = Some(userId.toString),
    details = if source == AuditSource.central then Json.Obj("changed" -> Json.Arr(Json.Str("claims"))) else Json.Obj(),
  )

  private val login = event(1, AuditSource.auth, at("10:00"), "login.completed")
  private val token = event(2, AuditSource.auth, at("09:00"), "token.issued")
  private val reset = event(3, AuditSource.central, at("09:30"), "admin.user-password.action:reset")
  private val claims = event(4, AuditSource.central, at("09:45"), "admin.user-claims.update")

  private def authPage(events: List[AuditEvent], next: Option[String]) =
    UserAuditEventsResponse(events = events, nextCursor = next, retentionFrom = authRetentionFrom)

  private val authSummary = Json.Obj("byKind" -> Json.Arr(), "sessions" -> Json.Obj("created" -> Json.Num(2), "ended" -> Json.Num(1)))

  private class Env:
    val repository = stub[CentralAuditRepository]
    val authClient = stub[AuthClient]
    val service = AuditReportService.Impl(repository, authClient, TestCentralConfig.config)

  private val clock = TestClock.setTime(now)

  def spec = suite("AuditReportService")(
    test("a page is auth's page and central's rows of the span it covers, newest first, with auth's summary and the administrators' actions") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(List(login, token), Some("more")))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(List(claims, reset))
        report <- env.service.userReport(userId, None, None, None, Some(2))
      yield
        val (from, until) = env.repository.userEvents.calls.head match
          case (_, from, until, _, _) => (from, until)
        assertTrue(
          report.events.map(_.id) == List(login, claims, reset, token).map(_.id),
          report.partial.isEmpty,
          // central's rows are asked for from where auth's page ends, to the end of the window, and the next page takes up there
          env.repository.userEvents.calls.map(c => (c._2, c._3)).contains((token.at, now.plus(1, ChronoUnit.MICROS))),
          from == token.at,
          until == now.plus(1, ChronoUnit.MICROS),
          report.nextCursor.flatMap(c => AuditReportService.decode(c).toOption).contains(
            AuditReportService.Cursor(until = token.at, auth = Some("more"), key = None, centralOnly = false),
          ),
          report.window.to == now,
          report.window.retentionFrom == now.minus(7, ChronoUnit.DAYS),
          report.summary.exists(_.fields.map(_._1).toSet == Set("byKind", "sessions", "adminActions")),
          report.summary.flatMap(_.fields.collectFirst { case ("adminActions", Json.Arr(actions)) => actions.size }).contains(2),
        )
    },
    test("the last page of auth's takes central's rows down to the start of the window, and has no next page") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(List(token), None))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(List(reset))
        report <- env.service.userReport(userId, Some(at("08:00")), Some(at("11:00")), None, None)
      yield assertTrue(
        report.nextCursor.isEmpty,
        report.window.from == at("08:00"),
        report.window.to == at("11:00"),
        env.repository.userEvents.calls.head._2 == at("08:00"),
        // auth is asked for the window the caller named
        env.authClient.getUserAuditEvents.calls.head._2 == UserAuditEventsQuery(
          Some(at("08:00")),
          Some(at("11:00")),
          None,
          AuditReportService.DefaultLimit,
        ),
      )
    },
    test("a window the caller did not name is auth's whole retained window: auth is not told one, and central's own retention bounds its rows") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(Nil, None))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(Nil)
        _ <- env.service.userReport(userId, None, None, None, None)
      yield assertTrue(
        env.authClient.getUserAuditEvents.calls.head._2 == UserAuditEventsQuery(None, None, None, AuditReportService.DefaultLimit),
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
        report <- env.service.userReport(userId, Some(Instant.parse("2020-01-01T00:00:00Z")), Some(now.plusSeconds(86400)), None, None)
      yield assertTrue(
        report.window.from == now.minus(7, ChronoUnit.DAYS),
        report.window.to == now,
        env.authClient.getUserAuditEvents.calls.head._2.from.contains(now.minus(7, ChronoUnit.DAYS)),
        env.authClient.getUserAuditEvents.calls.head._2.to.contains(now),
      )
    },
    test("the next page continues auth's cursor and central's rows from where the last one stopped, and carries no summary") {
      val env = Env()
      val cursor = AuditReportService.encode(AuditReportService.Cursor(until = token.at, auth = Some("more"), key = None, centralOnly = false))
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.succeedsWith(authPage(List(event(5, AuditSource.auth, at("08:00"), "session.ended")), None))
        _ <- env.repository.userEvents.succeedsWith(Nil)
        report <- env.service.userReport(userId, None, None, Some(cursor), Some(10))
      yield assertTrue(
        env.authClient.getUserAuditEvents.calls.head._2.cursor.contains("more"),
        env.authClient.getUserAuditSummary.calls.isEmpty,
        // exclusive at the instant the previous page ended at: its rows of that instant were on that page
        env.repository.userEvents.calls.map(_._3) == List(token.at),
        report.summary.isEmpty,
      )
    },
    test("without auth the report is central's rows alone, flagged, in pages of their own that go on by the key of the last row") {
      val env = Env()
      val rows = List(claims, reset, event(6, AuditSource.central, at("09:00"), "admin.user.viewed"))
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.failsWith(AuthAuditError.Unavailable("auth answered 503"))
        _ <- env.authClient.getUserAuditSummary.failsWith(AuthAuditError.Unavailable("auth answered 503"))
        _ <- env.repository.userEvents.succeedsWith(rows)
        first <- env.service.userReport(userId, None, None, None, Some(2))
        next = first.nextCursor.flatMap(c => AuditReportService.decode(c).toOption)
        second <- env.service.userReport(userId, None, None, first.nextCursor, Some(2))
      yield assertTrue(
        first.partial == List("auth"),
        first.events == List(claims, reset),
        // the actions of the administrators are the summary, which is all there is of it
        first.summary.exists(_.fields.map(_._1) == Chunk("adminActions")),
        next.exists(c => c.centralOnly && c.key.contains(AuditReportService.Key(reset.at, reset.id)) && c.auth.isEmpty),
        // asked one more than the page, to know there is a next
        env.repository.userEvents.calls.head._5 == 3,
        second.partial == List("auth"),
        // the second page is not a first one: no summary, and auth is not asked again
        second.summary.isEmpty,
        env.authClient.getUserAuditEvents.calls.size == 1,
        env.repository.userEvents.calls.last._4.contains(CentralAuditRepository.Key(reset.at, reset.id)),
      )
    },
    test("an answer of auth's that cannot be read leaves its part out like no answer at all") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.failsWith(RuntimeException("Auth response decode failed"))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(List(reset))
        report <- env.service.userReport(userId, None, None, None, None)
      yield assertTrue(report.partial == List("auth"), report.events == List(reset))
    },
    test("auth's refusal of the window is the caller's: a bad request, not a report without auth") {
      val env = Env()
      for
        _ <- clock
        _ <- env.authClient.getUserAuditEvents.failsWith(AuthAuditError.Invalid("from is before the retention"))
        _ <- env.authClient.getUserAuditSummary.succeedsWith(authSummary)
        _ <- env.repository.userEvents.succeedsWith(Nil)
        refused <- env.service.userReport(userId, None, None, None, None).either
      yield assertTrue(refused == Left(AuditReportError.Invalid("from is before the retention")))
    },
    test(
      "a page size outside 1 to 500, a cursor that is not ours and a window that ends before it starts are refused, and nothing is asked of anyone",
    ) {
      val env = Env()
      for
        _ <- clock
        zero <- env.service.userReport(userId, None, None, None, Some(0)).either
        tooMany <- env.service.userReport(userId, None, None, None, Some(501)).either
        garbage <- env.service.userReport(userId, None, None, Some("not-a-cursor"), None).either
        backwards <- env.service.userReport(userId, Some(at("11:00")), Some(at("10:00")), None, None).either
      yield assertTrue(
        zero.isLeft && zero.left.exists(_.isInstanceOf[AuditReportError.Invalid]),
        tooMany.left.exists(_.isInstanceOf[AuditReportError.Invalid]),
        garbage == Left(AuditReportError.Invalid("the cursor is not valid")),
        backwards == Left(AuditReportError.Invalid("to is before from")),
        env.authClient.getUserAuditEvents.calls.isEmpty,
        env.repository.userEvents.calls.isEmpty,
      )
    },
  )
