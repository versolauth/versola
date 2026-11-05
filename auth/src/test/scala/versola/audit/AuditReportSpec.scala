package versola.audit

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.audit.AuditReadRepository.{ClientCount, Factor, IpCount, KindCount, SessionCount, Summary, UserAgentCount}
import versola.audit.AuditReportService.{EventsPage, SummaryReport}
import versola.auth.TestEnvConfig
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import versola.oauth.logout.LogoutService
import versola.oauth.session.SessionService
import versola.user.UserController
import versola.user.UserRepository
import versola.util.audit.*
import versola.util.http.{NoopTracing, Observability}
import versola.util.net.GeoResolver
import versola.util.{CoreConfig, JWT, PageLimit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey

/** The per-user report auth serves central (`GET /users/audit-events` and `/users/audit-summary`, every input a typed query parameter): the window it accepts, the pages and
  * the cursor between them, the shape of the answers that central codes against, and who may ask. */
object AuditReportSpec extends ZIOSpecDefault, ZIOStubs:

  private val user = UUID.fromString("0192b3c0-0000-7000-8000-000000000001")
  private val start = Instant.parse("2026-11-03T12:00:00Z")
  private val retention = 7.days

  private def event(at: Instant, id: UUID = UUID.randomUUID()): AuditEvent =
    AuditEvent(
      id = id,
      at = at,
      tenantId = None,
      source = AuthAudit.source,
      nodeId = "node-1",
      kind = AuthAuditKinds.LoginCompleted,
      outcome = AuditOutcome.success,
      reason = None,
      severity = AuditSeverity.info,
      actorType = ActorType.user,
      actorId = Some(user.toString),
      userId = Some(user),
      clientId = Some("app"),
      sessionId = None,
      ip = Some("203.0.113.7"),
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = Json.Obj(),
    )

  private val settings = AuditEvents.Settings(
    retention = retention,
    overflow = AuditEvents.Overflow.block,
    blockTimeout = 2.seconds,
  )

  /** The country database as the report sees it: the addresses it knows. `resolve` is the lookup antifraud counts, which the report must
    * not use, so it is a defect here. */
  private def geo(known: (String, String)*): GeoResolver = new GeoResolver:
    override def resolve(ip: String): UIO[Option[String]] = ZIO.die(RuntimeException("the report counted a lookup"))
    override def peek(ip: String): UIO[Option[String]] = ZIO.succeed(known.toMap.get(ip))

  private def service(repository: Stub[AuditReadRepository], countries: GeoResolver = GeoResolver.off) =
    AuditReportService.Impl(
      repository,
      settings,
      countries,
      Unsafe.unsafe((u: Unsafe) ?=> Semaphore.unsafe.make(AuditReportService.ReadConcurrency)(using u)),
    )

  private val atStart = TestAspect.before(TestClock.setTime(start))

  /** A service whose every place for a read is taken. */
  private def serviceWithNoRoom(repository: Stub[AuditReadRepository]): UIO[AuditReportService] =
    for
      reads <- Semaphore.make(1)
      taken <- Promise.make[Nothing, Unit]
      _ <- reads.withPermit(taken.succeed(()) *> ZIO.never).forkDaemon
      _ <- taken.await
    yield AuditReportService.Impl(repository, settings, GeoResolver.off, reads)

  def spec = suite("AuditReport")(
    suite("AuditReportService")(
      test("reads the retained window up to now when no window is given, and answers where the retained window begins") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          page <- service(repository).events(user, None, None, None, None)
        yield assertTrue(
          repository.userEvents.calls == List((user, start.minusSeconds(retention.toSeconds), start, None, PageLimit.Default + 1)),
          page.retentionFrom == start.minusSeconds(retention.toSeconds),
          page.events.isEmpty,
          page.nextCursor.isEmpty,
        )
      },
      test("a window that begins before the retained one is refused rather than clipped: the answer would look complete and not be") {
        val repository = stub[AuditReadRepository]
        for
          early <- service(repository).events(user, Some(start.minusSeconds(retention.toSeconds + 1)), None, None, None).either
          summary <- service(repository).summary(user, Some(start.minusSeconds(retention.toSeconds + 1)), None).either
        yield assertTrue(
          early == Left(AuditReportError.WindowOutsideRetention),
          summary == Left(AuditReportError.WindowOutsideRetention),
          repository.userEvents.calls.isEmpty,
          repository.userSummary.calls.isEmpty,
        )
      },
      test("a window that ends in the future is refused, within a minute of the clock's skew it is not") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          skewed <- service(repository).events(user, None, Some(start.plusSeconds(30)), None, None).either
          future <- service(repository).events(user, None, Some(start.plusSeconds(120)), None, None).either
        yield assertTrue(skewed.isRight, future == Left(AuditReportError.WindowOutsideRetention))
      },
      test("a window that ends before it begins is refused") {
        val repository = stub[AuditReadRepository]
        for result <- service(repository).events(user, Some(start.minusSeconds(10)), Some(start.minusSeconds(20)), None, None).either
        yield assertTrue(result == Left(AuditReportError.WindowReversed))
      },
      test("a page of 1 and a page of 500 are served") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          top <- service(repository).events(user, None, None, None, Some(PageLimit(500))).either
          one <- service(repository).events(user, None, None, None, Some(PageLimit(1))).either
        yield assertTrue(top.isRight, one.isRight, repository.userEvents.calls.map(_._5) == List(501, 2))
      },
      test("at most ReadConcurrency reports are read at once: the next waits ReadWait for a place, then is refused as busy and never reads") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          _ <- repository.userSummary.succeedsWith(Summary(Nil, Nil, Nil, Nil, SessionCount(0, 0), Nil, Nil))
          full <- serviceWithNoRoom(repository)
          events <- full.events(user, None, None, None, None).either.fork
          summary <- full.summary(user, None, None).either.fork
          _ <- TestClock.adjust(AuditReportService.ReadWait.minus(1.milli))
          waiting <- events.poll
          _ <- TestClock.adjust(1.milli)
          eventsResult <- events.join
          summaryResult <- summary.join
        yield assertTrue(
          waiting.isEmpty,
          eventsResult == Left(AuditReportError.Busy),
          summaryResult == Left(AuditReportError.Busy),
          repository.userEvents.calls.isEmpty,
          repository.userSummary.calls.isEmpty,
        )
      },
      test("reports read up to the limit at once, and a place freed is taken by the next") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          reads <- Semaphore.make(AuditReportService.ReadConcurrency)
          service = AuditReportService.Impl(repository, settings, GeoResolver.off, reads)
          results <- ZIO.foreachPar(1 to 20)(_ => service.events(user, None, None, None, None).either)
        yield assertTrue(results.forall(_.isRight), repository.userEvents.calls.size == 20)
      },
      test("the events carry the country of their address, looked up without counting: a hit, a miss, a private address, no address") {
        val repository = stub[AuditReadRepository]
        val inGermany = event(start.minusSeconds(10)).copy(ip = Some("203.0.113.7"))
        val unknown = event(start.minusSeconds(20)).copy(ip = Some("198.51.100.9"))
        val private_ = event(start.minusSeconds(30)).copy(ip = Some("10.0.0.1"))
        val noAddress = event(start.minusSeconds(40)).copy(ip = None)
        for
          _ <- repository.userEvents.succeedsWith(List(inGermany, unknown, private_, noAddress))
          page <- service(repository, geo("203.0.113.7" -> "DE")).events(user, None, None, None, None)
        yield assertTrue(
          page.events.map(_.country) == List(Some("DE"), None, None, None),
          page.events.map(_.event) == List(inGermany, unknown, private_, noAddress),
        )
      },
      test("with no country database the report is as it was: no country anywhere, and no countries") {
        val repository = stub[AuditReadRepository]
        val shown = event(start.minusSeconds(10)).copy(ip = Some("203.0.113.7"))
        val ip = AuditReadRepository.IpCount("203.0.113.7", start, start, 3, country = None)
        for
          _ <- repository.userEvents.succeedsWith(List(shown))
          _ <- repository.userSummary.succeedsWith(Summary(Nil, Nil, List(ip), Nil, SessionCount(0, 0), Nil, List(shown)))
          page <- service(repository).events(user, None, None, None, None)
          report <- service(repository).summary(user, None, None)
        yield assertTrue(
          !page.toJson.contains("country"),
          !report.toJson.contains("\"country\""),
          report.countries.isEmpty,
          report.ips == List(ip),
        )
      },
      test("the summary names the country of each address and of the high events, and groups the addresses by country, most used first") {
        val repository = stub[AuditReadRepository]
        val ips = List(
          AuditReadRepository.IpCount("203.0.113.7", start.minusSeconds(100), start.minusSeconds(10), 5, country = None),
          AuditReadRepository.IpCount("198.51.100.9", start.minusSeconds(300), start.minusSeconds(200), 4, country = None),
          AuditReadRepository.IpCount("203.0.113.8", start.minusSeconds(50), start.minusSeconds(5), 3, country = None),
          AuditReadRepository.IpCount("10.0.0.1", start, start, 9, country = None),
        )
        val high = event(start.minusSeconds(10)).copy(ip = Some("198.51.100.9"))
        val known = geo("203.0.113.7" -> "DE", "203.0.113.8" -> "DE", "198.51.100.9" -> "FR")
        for
          _ <- repository.userSummary.succeedsWith(Summary(Nil, Nil, ips, Nil, SessionCount(0, 0), Nil, List(high)))
          report <- service(repository, known).summary(user, None, None)
        yield assertTrue(
          report.ips.map(_.country) == List(Some("DE"), Some("FR"), Some("DE"), None),
          report.countries == List(
            AuditReportService.CountryCount("DE", start.minusSeconds(100), start.minusSeconds(5), 8),
            AuditReportService.CountryCount("FR", start.minusSeconds(300), start.minusSeconds(200), 4),
          ),
          report.high.map(_.country) == List(Some("FR")),
        )
      },
      test("a page asks for one event more than it shows, and there is a next cursor exactly when it came back") {
        val repository = stub[AuditReadRepository]
        val newest = event(start.minusSeconds(10))
        val middle = event(start.minusSeconds(20))
        val oldest = event(start.minusSeconds(30))
        for
          _ <- repository.userEvents.succeedsWith(List(newest, middle, oldest))
          first <- service(repository).events(user, None, None, None, Some(PageLimit(2)))
          _ <- repository.userEvents.succeedsWith(List(oldest))
          last <- service(repository).events(user, None, None, first.nextCursor.flatMap(AuditCursor.parse(_).toOption), Some(PageLimit(2)))
        yield assertTrue(
          repository.userEvents.calls.map(_._5) == List(3, 3),
          first.events.map(_.event) == List(newest, middle),
          first.nextCursor.flatMap(AuditCursor.parse(_).toOption) == Some(AuditCursor(middle.at, middle.id)),
          // the next page resumes after the last event shown
          repository.userEvents.calls(1)._4 == Some(AuditCursor(middle.at, middle.id)),
          last.events.map(_.event) == List(oldest),
          last.nextCursor.isEmpty,
        )
      },
      test("the cursor is base64url of the time and the id, and a value that is not one does not decode") {
        val cursor = AuditCursor(Instant.parse("2026-11-03T11:59:50.123456Z"), UUID.fromString("0192b3c0-0000-7000-8000-0000000000ff"))
        val rendered = cursor.render
        val schema = zio.schema.Schema[AuditCursor]
        for
          decoded <- ZIO.succeed(schema.fromDynamic(schema.toDynamic(cursor)))
          garbage = AuditCursor.parse("not a cursor!")
          wrongShape = AuditCursor.parse(versola.util.Base64.urlEncode("a|b|c".getBytes))
          notATime = AuditCursor.parse(versola.util.Base64.urlEncode(s"yesterday|${cursor.id}".getBytes))
        yield assertTrue(
          AuditCursor.parse(rendered) == Right(cursor),
          decoded == Right(cursor),
          rendered.matches("[A-Za-z0-9_=-]+"),
          garbage.isLeft,
          wrongShape.isLeft,
          notATime.isLeft,
        )
      },
      test("the summary reads the window and answers it with the retained window's start") {
        val repository = stub[AuditReadRepository]
        val summary = Summary(
          byKind = List(KindCount("login.completed", "success", 3)),
          byClient = List(ClientCount("app", 3, 2)),
          ips = List(IpCount("203.0.113.7", start, start, 3, country = None)),
          userAgents = List(UserAgentCount("Chrome 130 / macOS", start, start, 3)),
          sessions = SessionCount(3, 1),
          factors = List(Factor("account.password_changed", start)),
          high = Nil,
        )
        for
          _ <- repository.userSummary.succeedsWith(summary)
          report <- service(repository).summary(user, None, None)
        yield assertTrue(
          repository.userSummary.calls == List((user, start.minusSeconds(retention.toSeconds), start)),
          report.window == AuditReportService.Window(start.minusSeconds(retention.toSeconds), start, start.minusSeconds(retention.toSeconds)),
          report.byKind == summary.byKind,
          report.sessions == SessionCount(3, 1),
        )
      },
    ) @@ atStart,
    suite("The report routes of UserController")(
      test("answers the events as central codes against them: events, nextCursor and retentionFrom") {
        val repository = stub[AuditReadRepository]
        val shown = event(start.minusSeconds(10))
        for
          _ <- repository.userEvents.succeedsWith(List(shown))
          response <- call(repository, s"/users/audit-events?id=$user")
          body <- response.body.asString
          json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
        yield assertTrue(
          response.status == Status.Ok,
          // `nextCursor` is there, as null, on the last page: what central codes against
          json.keys.toSet == Set("events", "nextCursor", "retentionFrom"),
          json.get("nextCursor").contains(Json.Null),
          json.get("events").flatMap(_.asArray).map(_.size) == Some(1),
          json.get("retentionFrom").flatMap(_.asString) == Some(start.minusSeconds(retention.toSeconds).toString),
          body.contains("\"kind\":\"login.completed\""),
          body.contains("\"userId\":\"" + user + "\""),
        )
      },
      test("passes the window, the cursor and the limit on, and answers 400 with the reason for a window that cannot be served") {
        val repository = stub[AuditReadRepository]
        val cursor = AuditCursor(start.minusSeconds(40), UUID.fromString("0192b3c0-0000-7000-8000-0000000000ff"))
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          ok <- call(repository, s"/users/audit-events?id=$user&from=${start.minusSeconds(60)}&to=$start&limit=5&cursor=${cursor.render}")
          outside <- call(repository, s"/users/audit-events?id=$user&from=${start.minusSeconds(retention.toSeconds + 1000)}")
          reversed <- call(repository, s"/users/audit-events?id=$user&from=$start&to=${start.minusSeconds(60)}")
          outsideText <- outside.body.asString
        yield assertTrue(
          ok.status == Status.Ok,
          repository.userEvents.calls.map(c => (c._2, c._3, c._4, c._5)) == List((start.minusSeconds(60), start, Some(cursor), 6)),
          outside.status == Status.BadRequest,
          outsideText.contains("before the start of the retained window"),
          reversed.status == Status.BadRequest,
          repository.userEvents.calls.size == 1,
        )
      },
      test("an input that does not decode is 400 before anything is read: the user, from, to, cursor and limit of both endpoints") {
        val repository = stub[AuditReadRepository]
        val paths = List(
          "/users/audit-events",
          "/users/audit-events?id=not-a-uuid",
          s"/users/audit-events?id=$user&from=yesterday",
          s"/users/audit-events?id=$user&to=2026-13-45",
          s"/users/audit-events?id=$user&cursor=not-a-cursor!",
          s"/users/audit-events?id=$user&limit=abc",
          s"/users/audit-events?id=$user&limit=0",
          s"/users/audit-events?id=$user&limit=501",
          "/users/audit-summary",
          "/users/audit-summary?id=not-a-uuid",
          s"/users/audit-summary?id=$user&from=yesterday",
          s"/users/audit-summary?id=$user&to=tomorrow",
        )
        for
          responses <- ZIO.foreach(paths)(call(repository, _))
          // the routes of the first version are not routes of this service
          inPath <- call(repository, s"/audit/events?userId=$user")
        yield assertTrue(
          responses.map(_.status).forall(_ == Status.BadRequest),
          inPath.status == Status.NotFound,
          repository.userEvents.calls.isEmpty,
          repository.userSummary.calls.isEmpty,
        )
      },
      test("the limit of a page is 1 to 500: both ends are served") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          one <- call(repository, s"/users/audit-events?id=$user&limit=1")
          top <- call(repository, s"/users/audit-events?id=$user&limit=500")
        yield assertTrue(
          one.status == Status.Ok,
          top.status == Status.Ok,
          repository.userEvents.calls.map(_._5) == List(2, 501),
        )
      },
      test("serves the country as a member of the event it belongs to, and leaves it out when there is none") {
        val repository = stub[AuditReadRepository]
        val inGermany = event(start.minusSeconds(10)).copy(ip = Some("203.0.113.7"))
        val unknown = event(start.minusSeconds(20)).copy(ip = Some("198.51.100.9"))
        for
          _ <- repository.userEvents.succeedsWith(List(inGermany, unknown))
          response <- callWith(service(repository, geo("203.0.113.7" -> "DE")), s"/users/audit-events?id=$user")
          body <- response.body.asString
          json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
          items = json.get("events").flatMap(_.asArray).map(_.toList.flatMap(_.asObject)).getOrElse(Nil)
        yield assertTrue(
          response.status == Status.Ok,
          items.map(_.get("country").flatMap(_.asString)) == List(Some("DE"), None),
          items.map(_.keys.contains("country")) == List(true, false),
          items.forall(_.get("ip").isDefined),
        )
      },
      test("answers the summary with its blocks") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userSummary.succeedsWith(Summary(Nil, Nil, Nil, Nil, SessionCount(0, 0), Nil, Nil))
          response <- call(repository, s"/users/audit-summary?id=$user")
          body <- response.body.asString
          json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
        yield assertTrue(
          response.status == Status.Ok,
          json.keys.toSet == Set("window", "byKind", "byClient", "ips", "userAgents", "sessions", "countries", "factors", "high"),
          json.get("window").flatMap(_.asObject).map(_.keys.toSet) == Some(Set("from", "to", "retentionFrom")),
        )
      },
      test("answers 503 with Retry-After, and the code audit_report_busy, when no place for a read comes free in time") {
        val repository = stub[AuditReadRepository]
        for
          full <- serviceWithNoRoom(repository)
          response <- callWith(full, s"/users/audit-events?id=$user").fork
          _ <- TestClock.adjust(AuditReportService.ReadWait)
          answered <- response.join
        yield assertTrue(
          answered.status == Status.ServiceUnavailable,
          answered.headers.get("Retry-After").contains("1"),
          repository.userEvents.calls.isEmpty,
        )
      },
      test("is for the service token alone: no token, a token signed with another key, and a failing read all refuse") {
        val repository = stub[AuditReadRepository]
        for
          _ <- repository.userEvents.succeedsWith(Nil)
          none <- call(repository, s"/users/audit-events?id=$user", signedWith = None)
          wrong <- call(repository, s"/users/audit-events?id=$user", signedWith = Some(wrongKey))
          summaryNone <- call(repository, s"/users/audit-summary?id=$user", signedWith = None)
        yield assertTrue(
          none.status == Status.Unauthorized,
          wrong.status == Status.Unauthorized,
          summaryNone.status == Status.Unauthorized,
          repository.userEvents.calls.isEmpty,
          repository.userSummary.calls.isEmpty,
        )
      },
    ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging @@ atStart,
  )

  /** What the other routes of the controller need and the report never touches: a call of one of them would fail the test. */
  private val unused: ZEnvironment[
    UserRepository & LogoutService & SessionService & ChallengeThrottleRepository & PasskeyRepository & PasswordService & AuditEvents,
  ] =
    ZEnvironment[UserRepository](stub[UserRepository]) ++ ZEnvironment[LogoutService](stub[LogoutService]) ++
      ZEnvironment[SessionService](stub[SessionService]) ++ ZEnvironment[ChallengeThrottleRepository](stub[ChallengeThrottleRepository]) ++
      ZEnvironment[PasskeyRepository](stub[PasskeyRepository]) ++ ZEnvironment[PasswordService](stub[PasswordService]) ++
      ZEnvironment[AuditEvents](stub[AuditEvents])

  private val key: SecretKey = TestEnvConfig.coreConfig.central.secretKey
  private val wrongKey: SecretKey = javax.crypto.spec.SecretKeySpec(Array.fill(32)(99.toByte), "AES")

  private def tokenFor(secret: SecretKey): Task[String] =
    JWT.serialize(JWT.Claims("central", "central", List("auth"), Json.Obj()), 10.minutes, JWT.Signature.Symmetric(secret))

  private def call(
      repository: Stub[AuditReadRepository],
      path: String,
      signedWith: Option[SecretKey] = Some(key),
  ): ZIO[Client & TestClient & Scope, Throwable, Response] = callWith(service(repository), path, signedWith)

  private def callWith(
      reports: AuditReportService,
      path: String,
      signedWith: Option[SecretKey] = Some(key),
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      noop <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          UserController.routes.provideEnvironment(
            ZEnvironment[AuditReportService](reports) ++ ZEnvironment[CoreConfig](TestEnvConfig.coreConfig) ++ unused ++ noop,
          ),
        ),
      )
      url = URL.decode(path).toOption.get
      request = Request(method = Method.GET, url = url)
      token <- ZIO.foreach(signedWith)(tokenFor)
      response <- client.batched(token.fold(request)(t => request.addHeader(Header.Authorization.Bearer(t))))
    yield response
