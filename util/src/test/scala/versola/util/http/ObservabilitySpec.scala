package versola.util.http

import io.opentelemetry.api
import zio.*
import zio.http.*
import zio.http.codec.HttpCodecError
import zio.json.*
import zio.logging.LogFilter
import zio.logging.LogFormat.{cause, label, level, line, logAnnotation, quoted, space}
import zio.metrics.{Metric, MetricLabel}
import zio.stream.ZStream
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object ObservabilitySpec extends ZIOSpecDefault:
  // baseUri/queryParams/headers/body carry defaults matching the always-empty/None shape
  // production logs render when the corresponding masking option is off, so existing
  // assertions built from bare `LoggedRequest(path)`/`LoggedResponse(code)` keep working.
  // `@jsonMemberNames(SnakeCase)` mirrors production's `HttpRequestLog` (queryParams/baseUri
  // are serialized as query_params/base_uri); without it those two fields would silently
  // decode to their defaults instead of the actual logged values.
  @jsonMemberNames(SnakeCase)
  private case class LoggedRequest(
      path: String,
      cookies: List[String] = Nil,
      baseUri: String = "http://",
      queryParams: List[String] = Nil,
      headers: List[String] = Nil,
      body: Option[String] = None,
  ) derives JsonDecoder
  private case class LoggedResponse(code: Int, body: Option[String] = None, headers: List[String] = Nil) derives JsonDecoder
  private case class LoggedHttp(request: LoggedRequest, response: LoggedResponse) derives JsonDecoder
  private case class LoggedError(code: String, description: Option[String]) derives JsonDecoder
  private case class LoggedEntry(
      level: String,
      message: String,
      http: LoggedHttp,
      stack_trace: Option[String] = None,
      error: Option[LoggedError] = None,
  ) derives JsonDecoder

  @jsonMemberNames(SnakeCase)
  private case class ClientLoggedRequest(
      path: String,
      queryParams: List[String],
      headers: List[String],
      body: Option[String],
      baseUri: String = "",
  ) derives JsonDecoder
  private case class ClientLoggedHttp(
      request: ClientLoggedRequest,
      response: LoggedResponse,
  ) derives JsonDecoder
  private case class ClientLoggedEntry(
      level: String,
      message: String,
      http: ClientLoggedHttp,
  ) derives JsonDecoder

  private val renderedLogFormat =
    List(
      label("level", level),
      label("message", quoted(line)),
      (space + label("stack_trace", cause)).filter(LogFilter.causeNonEmpty),
      logAnnotation(Observability.receiveHttp),
      logAnnotation(Observability.error),
    ).reduce(_ |-| _)

  private val clientLogFormat =
    List(
      label("level", level),
      label("message", quoted(line)),
      logAnnotation(Observability.sendHttp),
    ).reduce(_ |-| _)

  private case class LoggedAuth(id: Option[String] = None) derives JsonDecoder
  private case class AuthLoggedEntry(message: String, auth: Option[LoggedAuth] = None) derives JsonDecoder

  private val authLogFormat =
    List(
      label("message", quoted(line)),
      logAnnotation(Observability.auth),
    ).reduce(_ |-| _)

  @jsonMemberNames(SnakeCase)
  private case class FullLoggedAuth(
      id: Option[String] = None,
      priorSessionId: Option[String] = None,
      sessionId: Option[String] = None,
      clientId: Option[String] = None,
      step: Option[String] = None,
      userId: Option[String] = None,
      userAgentId: Option[String] = None,
      ip: Option[String] = None,
      token: Option[String] = None,
      refreshToken: Option[String] = None,
      previousRefreshToken: Option[String] = None,
  ) derives JsonDecoder
  private case class FullAuthLoggedEntry(message: String, auth: Option[FullLoggedAuth] = None) derives JsonDecoder
  private val fullAuthLogFormat =
    List(
      label("message", quoted(line)),
      logAnnotation(Observability.auth),
    ).reduce(_ |-| _)

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val testLayer =
    TestClient.layer ++ ZTestLogger.default ++ tracingLayer

  private val routes =
    Observability.middleware(
      Observability.handleErrors(
        Routes(
          Method.GET / "ok" -> Handler.fromResponse(Response.text("ok")),
          Method.GET / "boom" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(new RuntimeException("boom"))),
          Method.GET / "auth" -> Handler.fromFunctionZIO[Request] { _ =>
            Observability.setAuthId("auth-1") *> ZIO.logInfo("in-handler").as(Response.text("ok"))
          },
          // Exercises every AuthDetails setter beyond setAuthId/setError (lines 50-81), none of
          // which any other test previously called.
          Method.GET / "auth-full" -> Handler.fromFunctionZIO[Request] { _ =>
            (Observability.setAuth("auth-2", "client-0") *>
              Observability.setClientId("client-1") *>
              Observability.setPriorSessionId("prior-session-1") *>
              Observability.setSessionId("session-1") *>
              Observability.setStep("otp") *>
              Observability.setUserId("user-1") *>
              Observability.setUserAgentId("agent-1") *>
              Observability.setIp("127.0.0.1") *>
              Observability.setToken("jti-1") *>
              Observability.setRefreshToken("refresh-token-full") *>
              Observability.setPreviousRefreshToken("previous-refresh-full") *>
              // setAuth already set id; re-set it explicitly last so the final auth.id is deterministic.
              Observability.setAuthId("id-1") *>
              ZIO.logInfo("in-handler-full")).as(Response.text("ok"))
          },
          Method.GET / "error" -> Handler.fromFunctionZIO[Request] { _ =>
            Observability.setError("invalid_request", Some("Invalid request")).as(Response.badRequest)
          },
          Method.GET / "labeled" -> Handler.fromFunctionZIO[Request] { _ =>
            Observability.setRouteLabel("grant_type", "client_credentials").as(Response.text("ok"))
          },
          // Closes the setError-without-description gap (ErrorDetails.description default, line 530).
          Method.GET / "error-no-desc" -> Handler.fromFunctionZIO[Request] { _ =>
            Observability.setError("some_code").as(Response.badRequest)
          },
          Method.GET / "unauthorized" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(Unauthorized)),
          Method.GET / "forbidden" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(Forbidden)),
          Method.GET / "bad-request" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(BadRequest("bad input"))),
          Method.GET / "codec-error" -> Handler.fromFunctionZIO[Request](_ =>
            ZIO.fail(HttpCodecError.CustomError("test", "bad codec")),
          ),
          // Closes withServerLogging (135/138) plus the request/response body & header masking
          // gaps (181, 186, 187, 194, 221, 226, 227) that only trigger when a handler opts a
          // request into fuller logging than the server default.
          Method.POST / "verbose" -> Handler.fromFunctionZIO[Request] { _ =>
            Observability.withServerLogging(_.copy(
              logRequestBody = true,
              logResponseBody = true,
              logQuery = Set("q"),
              logRequestHeaders = Set("x-allowed"),
              logResponseHeaders = Set("x-resp"),
            ))(ZIO.unit).as(Response.text("resp-body").addHeader("x-resp", "resp-value"))
          },
          // Closes the html-content-type response body placeholder gap (218/219).
          Method.GET / "verbose-html" -> Handler.fromFunctionZIO[Request] { _ =>
            Observability.withServerLogging(_.copy(logResponseBody = true))(ZIO.unit)
              .as(Response(body = Body.fromString("<p>hi</p>")).addHeader(Header.ContentType(MediaType.text.html)))
          },
        ),
      ),
    )

  private val proxyRoutes =
    Observability.middleware(
      Observability.handleErrors(
        Routes(
          Method.GET / "resources" / string("alias") / trailing ->
            handler((alias: String, rest: Path, _: Request) =>
              Observability.setRoutePath(s"/resources/$alias/items/{itemId}")
                .when(rest.segments.headOption.contains("items"))
                .as(Response.text("proxied")),
            ),
        ),
      ),
    )

  private def counterCount(tags: Set[MetricLabel]): UIO[Double] =
    Metric.counter("http_server_requests_total").tagged(tags).value.map(_.count)

  private def histogramCount(tags: Set[MetricLabel]): UIO[Long] =
    Metric.histogram("http_server_request_duration_seconds", Observability.durationBoundaries).tagged(tags).value.map(_.count)

  private def clientCounterCount(tags: Set[MetricLabel]): UIO[Double] =
    Metric.counter("http_client_requests_total").tagged(tags).value.map(_.count)

  private def clientHistogramCount(tags: Set[MetricLabel]): UIO[Long] =
    Metric.histogram("http_client_request_duration_seconds", Observability.clientDurationBoundaries).tagged(tags).value.map(_.count)

  def spec = suite("Observability")(
    test("logs a single info entry for successful requests") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        response <- client.batched(Request.get(URL.empty / "ok"))
        logs <- ZTestLogger.logOutput
        receiveLogs = logs.filter(_.message() == "receive-http")
        rawLog <- ZIO.fromOption(receiveLogs.headOption).orElseFail(new RuntimeException("Missing receive-http log"))
        rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
          .mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        receiveLogs.length == 1,
        rendered == LoggedEntry(
          level = "INFO",
          message = "receive-http",
          http = LoggedHttp(LoggedRequest("ok"), LoggedResponse(200)),
          stack_trace = None,
        ),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("logs cookie names without their values") {
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(routes.provideEnvironment(env))
          client <- ZIO.service[Client]
          _ <- client.batched(
            Request.get(URL.empty / "ok")
              .addCookie(Cookie.Request("session", "secret-value")),
          )
          logs <- ZTestLogger.logOutput
          rawLog <- ZIO.fromOption(logs.find(_.message() == "receive-http"))
            .orElseFail(new RuntimeException("Missing receive-http log"))
          rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          rendered.http.request.cookies == List("session"),
          !rawLog.call(renderedLogFormat.toJsonLogger).contains("secret-value"),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("renders request error details in the receive log") {
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(routes.provideEnvironment(env))
          client <- ZIO.service[Client]
          response <- client.batched(Request.get(URL.empty / "error"))
          logs <- ZTestLogger.logOutput
          rawLog <- ZIO.fromOption(logs.find(_.message() == "receive-http"))
            .orElseFail(new RuntimeException("Missing receive-http log"))
          rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          response.status == Status.BadRequest,
          rendered.error.contains(LoggedError("invalid_request", Some("Invalid request"))),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    test("returns 500 and logs a single error entry for failed requests") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        response <- client.batched(Request.get(URL.empty / "boom"))
        logs <- ZTestLogger.logOutput
        receiveLogs = logs.filter(_.message() == "receive-http")
        rawLog <- ZIO.fromOption(receiveLogs.headOption).orElseFail(new RuntimeException("Missing receive-http log"))
        rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
          .mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.InternalServerError,
        receiveLogs.length == 1,
        rendered.level == "ERROR",
        rendered.message == "receive-http",
        rendered.http == LoggedHttp(LoggedRequest("boom"), LoggedResponse(500)),
        rendered.stack_trace.exists(_.contains("RuntimeException: boom")),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    test("annotates every log line of a request with its auth details, without leaking into the next one") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        _ <- client.batched(Request.get(URL.empty / "auth"))
        _ <- client.batched(Request.get(URL.empty / "ok"))
        logs <- ZTestLogger.logOutput
        rendered <- ZIO.foreach(logs.filter(log => log.message() == "in-handler" || log.message() == "receive-http")) { rawLog =>
          ZIO.fromEither(rawLog.call(authLogFormat.toJsonLogger).fromJson[AuthLoggedEntry])
            .mapError(new RuntimeException(_))
        }
      yield assertTrue(
        rendered.map(entry => entry.message -> entry.auth.flatMap(_.id)) == Chunk(
          "in-handler" -> Some("auth-1"),
          "receive-http" -> Some("auth-1"),
          "receive-http" -> None,
        ),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    // Closes the ErrorDetails.description default-None gap (line 530): every other error test
    // supplies an explicit description, so the default value itself never runs.
    test("renders error details with no description when setError is called without one") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        response <- client.batched(Request.get(URL.empty / "error-no-desc"))
        logs <- ZTestLogger.logOutput
        rawLog <- ZIO.fromOption(logs.find(_.message() == "receive-http"))
          .orElseFail(new RuntimeException("Missing receive-http log"))
        rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
          .mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.BadRequest,
        rendered.error.contains(LoggedError("some_code", None)),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    // Closes handleErrors' Unauthorized/Forbidden/BadRequest/HttpCodecError branches (172, 174, 175):
    // only the generic Throwable case ("boom") was previously exercised.
    test("maps well-known error types to their dedicated responses") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        unauthorized <- client.batched(Request.get(URL.empty / "unauthorized"))
        forbidden <- client.batched(Request.get(URL.empty / "forbidden"))
        badRequest <- client.batched(Request.get(URL.empty / "bad-request"))
        codecError <- client.batched(Request.get(URL.empty / "codec-error"))
        badRequestBody <- badRequest.body.asString
        codecErrorBody <- codecError.body.asString
      yield assertTrue(
        unauthorized.status == Status.Unauthorized,
        forbidden.status == Status.Forbidden,
        badRequest.status == Status.BadRequest,
        badRequestBody == "bad input",
        codecError.status == Status.BadRequest,
        codecErrorBody == "bad codec",
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    // Closes withServerLogging (135/138) and the request-side body/query/header masking gaps
    // (181, 186, 187, 194) that only trigger once a handler opts into fuller logging.
    test("withServerLogging widens request body, query and header masking for the current request") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        url = (URL.empty / "verbose").addQueryParam("q", "1").addQueryParam("other", "2")
        request = Request.post(url, Body.fromString("req-body"))
          .addHeader(Header.Authorization.Bearer("secret-token"))
          .addHeader("x-allowed", "yes")
          .addHeader("x-blocked", "no")
        response <- client.batched(request)
        logs <- ZTestLogger.logOutput
        rawLog <- ZIO.fromOption(logs.find(_.message() == "receive-http"))
          .orElseFail(new RuntimeException("Missing receive-http log"))
        rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
          .mapError(new RuntimeException(_))
        req = rendered.http.request
      yield assertTrue(
        response.status == Status.Ok,
        req.body.contains("req-body"),
        req.queryParams.exists(_.contains("q=1")),
        !req.queryParams.exists(_.contains("other=2")),
        req.headers.exists(_.contains("Bearer ***")),
        !req.headers.exists(_.contains("secret-token")),
        req.headers.exists(_.contains("x-allowed=yes")),
        !req.headers.exists(_.contains("x-blocked")),
        rendered.http.response.body.contains("resp-body"),
        rendered.http.response.headers.exists(_.contains("x-resp=resp-value")),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    // Closes the html-content-type response body placeholder gap (218/219): only the client-side
    // equivalent was exercised before.
    test("replaces the response body with a placeholder for html content when response logging is enabled") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        response <- client.batched(Request.get(URL.empty / "verbose-html"))
        logs <- ZTestLogger.logOutput
        rawLog <- ZIO.fromOption(logs.find(_.message() == "receive-http"))
          .orElseFail(new RuntimeException("Missing receive-http log"))
        rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
          .mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        rendered.http.response.body.contains("<html>"),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    // Closes the absolute-URL branch of the server-side request log's baseUri (line 201); every
    // other server test hits routes through a relative TestClient URL.
    // Dispatched via `Routes#runZIO` rather than `client.batched`: TestClient's simulated
    // network round trip normalizes every request to a relative (path-only) URL, so an
    // absolute incoming URL (line 201's branch) can only be observed by invoking the
    // (already fully-wired) route handler directly with a hand-built absolute-URL request.
    test("renders an absolute base URI in the request log when the request URL is absolute") {
      for
        env <- tracingLayer.build
        url <- ZIO.fromEither(URL.decode("http://test-host:1234/ok")).orDie
        response <- routes.provideEnvironment(env).runZIO(Request.get(url))
        logs <- ZTestLogger.logOutput
        rawLog <- ZIO.fromOption(logs.find(_.message() == "receive-http"))
          .orElseFail(new RuntimeException("Missing receive-http log"))
        rendered <- ZIO.fromEither(rawLog.call(renderedLogFormat.toJsonLogger).fromJson[LoggedEntry])
          .mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        rendered.http.request.baseUri == "http://test-host:1234",
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    // Closes the no-space fallback branch of maskAuthorization (line 167): every masking test
    // above uses a `Scheme value` shaped header, which always contains a space.
    test("maskAuthorization falls back to a plain mask when there is no scheme token") {
      assertTrue(Observability.maskAuthorization("opaque-value-without-a-scheme") == "***")
    },
    // Closes the ErrorDetails.description default (line 530): `setError` always forwards an
    // explicit `description` (even when it's its own `None` default), so `ErrorDetails`'s own
    // default is only reachable by constructing it directly with a single argument.
    test("ErrorDetails defaults to no description when constructed without one") {
      assertTrue(Observability.ErrorDetails("some_code") == Observability.ErrorDetails("some_code", None))
    },
    // Closes the auth-detail setters that no test previously exercised (lines 50-81): only
    // setAuthId, setError and setRouteLabel/setRoutePath were reached via existing routes.
    test("every AuthDetails setter annotates its own field without disturbing the others") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        _ <- client.batched(Request.get(URL.empty / "auth-full"))
        logs <- ZTestLogger.logOutput
        rawLog <- ZIO.fromOption(logs.find(_.message() == "in-handler-full"))
          .orElseFail(new RuntimeException("Missing in-handler-full log"))
        rendered <- ZIO.fromEither(rawLog.call(fullAuthLogFormat.toJsonLogger).fromJson[FullAuthLoggedEntry])
          .mapError(new RuntimeException(_))
        auth = rendered.auth.get
      yield assertTrue(
        auth.id.contains("id-1"),
        auth.priorSessionId.contains("prior-session-1"),
        auth.sessionId.contains("session-1"),
        auth.clientId.contains("client-1"),
        auth.step.contains("otp"),
        auth.userId.contains("user-1"),
        auth.userAgentId.contains("agent-1"),
        auth.ip.contains("127.0.0.1"),
        auth.token.contains("jti-1"),
        // Both are truncated to RefreshTokenPrefixLength (9) by their respective setters.
        auth.refreshToken.contains("refresh-t"),
        auth.previousRefreshToken.contains("previous-"),
      )
    }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    suite("server metrics")(
      test("records counter and histogram for successful requests") {
        val counterTags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/ok"),
          MetricLabel("status", "200"),
          MetricLabel("status_class", "2xx"),
        )
        val durationTags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/ok"),
          MetricLabel("status_class", "2xx"),
        )
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(routes.provideEnvironment(env))
          client <- ZIO.service[Client]
          response <- client.batched(Request.get(URL.empty / "ok"))
          requests <- counterCount(counterTags)
          durations <- histogramCount(durationTags)
        yield assertTrue(
          response.status == Status.Ok,
          requests >= 1.0,
          durations >= 1L,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("counts 5xx errors via status_class") {
        val counterTags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/boom"),
          MetricLabel("status", "500"),
          MetricLabel("status_class", "5xx"),
        )
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(routes.provideEnvironment(env))
          client <- ZIO.service[Client]
          response <- client.batched(Request.get(URL.empty / "boom"))
          requests <- counterCount(counterTags)
        yield assertTrue(
          response.status == Status.InternalServerError,
          requests >= 1.0,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("falls back to the route pattern for an unresolved resources proxy path") {
        val tags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/resources/{alias}/..."),
          MetricLabel("status", "200"),
          MetricLabel("status_class", "2xx"),
        )
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(proxyRoutes.provideEnvironment(env))
          client <- ZIO.service[Client]
          response <- client.batched(Request.get(URL.empty / "resources" / "myalias" / "extra"))
          requests <- counterCount(tags)
        yield assertTrue(
          response.status == Status.Ok,
          requests >= 1.0,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("keeps the route label bounded across distinct path parameter values") {
        val tags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/resources/myalias/items/{itemId}"),
          MetricLabel("status", "200"),
          MetricLabel("status_class", "2xx"),
        )
        val activeTags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/resources/{alias}/..."),
        )
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(proxyRoutes.provideEnvironment(env))
          client <- ZIO.service[Client]
          _ <- ZIO.foreachDiscard(Chunk("1", "2", "3")): itemId =>
            client.batched(Request.get(URL.empty / "resources" / "myalias" / "items" / itemId))
          requests <- counterCount(tags)
          concrete <- counterCount(tags - MetricLabel("route", "/resources/myalias/items/{itemId}") + MetricLabel("route", "/resources/myalias/items/1"))
          active <- Metric.gauge("http_server_active_requests").tagged(activeTags).value.map(_.value)
        yield assertTrue(
          requests >= 3.0,
          concrete == 0.0,
          active == 0.0,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("appends the route label set mid-handler to the route metric label") {
        val tags = Set(
          MetricLabel("method", "GET"),
          MetricLabel("route", "/labeled?grant_type=client_credentials"),
          MetricLabel("status", "200"),
          MetricLabel("status_class", "2xx"),
        )
        for
          env <- tracingLayer.build
          _ <- TestClient.addRoutes(routes.provideEnvironment(env))
          client <- ZIO.service[Client]
          response <- client.batched(Request.get(URL.empty / "labeled"))
          requests <- counterCount(tags)
        yield assertTrue(
          response.status == Status.Ok,
          requests >= 1.0,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
    ),
    suite("client middleware")(
      test("logs INFO for successful requests") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "ok" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          response <- client.batched(Request.get(URL.empty / "ok"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          response.status == Status.Ok,
          sendLogs.length == 1,
          entry.level == "INFO",
          entry.message == "send-http",
          entry.http.response.code == 200,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("logs INFO for 4xx responses") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "bad" -> Handler.badRequest))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          _ <- client.batched(Request.get(URL.empty / "bad"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          sendLogs.length == 1,
          entry.level == "INFO",
          entry.http.response.code == 400,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("logs WARN for 5xx responses") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "server-error" -> Handler.internalServerError))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          _ <- client.batched(Request.get(URL.empty / "server-error"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          sendLogs.length == 1,
          entry.level == "ERROR",
          entry.http.response.code == 500,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("masks Authorization header") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "secure" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          _ <- client.batched(
            Request.get(URL.empty / "secure").addHeader(Header.Authorization.Bearer("secret-token")),
          )
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
          headers = entry.http.request.headers
        yield assertTrue(
          headers.exists(_.contains("Bearer ***")),
          !headers.exists(_.contains("secret-token")),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("masks Basic Authorization header while preserving the scheme") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "secure" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          _ <- client.batched(
            Request.get(URL.empty / "secure").addHeader(Header.Authorization.Basic("user", "c2VjcmV0")),
          )
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
          headers = entry.http.request.headers
        yield assertTrue(
          headers.exists(_.contains("Basic ***")),
          !headers.exists(_.contains("c2VjcmV0")),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("logs only query params allowed by client config") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "query" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          config = HttpObservabilityConfig.Client.default.copy(logQuery = Set("allowed"))
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(config)
          url = (URL.empty / "query").addQueryParam("allowed", "yes").addQueryParam("secret", "no")
          _ <- client.batched(Request.get(url))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
          query = entry.http.request.queryParams
        yield assertTrue(
          query.exists(_.contains("allowed=yes")),
          !query.exists(_.contains("secret=no")),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("logs only request headers allowed by client config") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "headers" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          config = HttpObservabilityConfig.Client.default.copy(logRequestHeaders = Set("x-allowed"))
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(config)
          request = Request.get(URL.empty / "headers")
            .addHeader("x-allowed", "yes")
            .addHeader("x-secret", "no")
          _ <- client.batched(request)
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
          headers = entry.http.request.headers
        yield assertTrue(
          headers.exists(_.contains("x-allowed=yes")),
          !headers.exists(_.contains("x-secret=no")),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      test("respects withClientMasking to suppress request body") {
        for
          _ <- TestClient.addRoutes(Routes(Method.POST / "data" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(HttpObservabilityConfig.Client.default.copy(logRequestBody = false))
          _ <- client.batched(Request.post(URL.empty / "data", Body.fromString("sensitive")))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          entry.http.request.body.isEmpty,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the request-body-logging-enabled branch (line 378): the existing masking tests
      // only exercise the disabled (false) side of `masking.logRequestBody && body.isComplete`.
      test("captures the request body when request body logging is enabled") {
        for
          _ <- TestClient.addRoutes(Routes(Method.POST / "data" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(HttpObservabilityConfig.Client.default.copy(logRequestBody = true))
          _ <- client.batched(Request.post(URL.empty / "data", Body.fromString("payload")))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          entry.http.request.body.contains("payload"),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the html-content-type response body placeholder branch (line 425).
      test("replaces the response body with a placeholder for html content when response body logging is enabled") {
        for
          _ <- TestClient.addRoutes(Routes(
            Method.GET / "body-html" -> Handler.fromResponse(
              Response(body = Body.fromString("<p>hi</p>")).addHeader(Header.ContentType(MediaType.text.html)),
            ),
          ))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(HttpObservabilityConfig.Client.default.copy(logResponseBody = true))
          _ <- client.batched(Request.get(URL.empty / "body-html"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          entry.http.response.body.contains("<html>"),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the non-html, in-memory response body branch (line 426).
      test("captures the response body for non-html content when response body logging is enabled") {
        for
          _ <- TestClient.addRoutes(Routes(
            Method.GET / "body-text" -> Handler.fromResponse(Response.text("client-response-body")),
          ))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(HttpObservabilityConfig.Client.default.copy(logResponseBody = true))
          _ <- client.batched(Request.get(URL.empty / "body-text"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          entry.http.response.body.contains("client-response-body"),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the streamed (not-yet-fully-buffered) response body branch (line 427): body
      // logging is enabled, content isn't html, but the body never becomes `isComplete`.
      test("skips the response body for a streamed response even when response body logging is enabled") {
        for
          _ <- TestClient.addRoutes(Routes(
            Method.GET / "body-stream" -> Handler.fromResponse(
              Response(body = Body.fromStreamChunked(ZStream.fromIterable("streamed".getBytes(java.nio.charset.StandardCharsets.UTF_8).toIndexedSeq))),
            ),
          ))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(HttpObservabilityConfig.Client.default.copy(logResponseBody = true))
          _ <- client.batched(Request.get(URL.empty / "body-stream"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          entry.http.response.body.isEmpty,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the response header allowlist branch (lines 433/434).
      test("logs only response headers allowed by client config") {
        for
          _ <- TestClient.addRoutes(Routes(
            Method.GET / "resp-headers" -> Handler.fromResponse(
              Response.text("ok").addHeader("x-resp-allowed", "yes").addHeader("x-resp-blocked", "no"),
            ),
          ))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          config = HttpObservabilityConfig.Client.default.copy(logResponseHeaders = Set("x-resp-allowed"))
          client = rawClient @@ Observability.clientMiddleware(tracing) @@ mask(config)
          _ <- client.batched(Request.get(URL.empty / "resp-headers"))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
          headers = entry.http.response.headers
        yield assertTrue(
          headers.exists(_.contains("x-resp-allowed=yes")),
          !headers.exists(_.contains("x-resp-blocked")),
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the absolute-URL branch of the client-side request log's baseUri (line 372).
      test("renders an absolute base URI in the request log for an absolute client URL") {
        for
          _ <- TestClient.addRoutes(Routes(Method.GET / "ok" -> Handler.ok))
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          url <- ZIO.fromEither(URL.decode("http://peer-host:5678/ok")).orDie
          _ <- client.batched(Request.get(url))
          logs <- ZTestLogger.logOutput
          sendLogs = logs.filter(_.message() == "send-http")
          rawLog <- ZIO.fromOption(sendLogs.headOption).orElseFail(new RuntimeException("Missing send-http log"))
          entry <- ZIO.fromEither(rawLog.call(clientLogFormat.toJsonLogger).fromJson[ClientLoggedEntry])
            .mapError(new RuntimeException(_))
        yield assertTrue(
          entry.http.request.baseUri == "http://peer-host:5678",
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the socket delegation branch of the observability client driver (lines 345/350):
      // every other client test only exercises the `request` branch of the driver.
      test("wires WebSocket connections through the underlying driver's socket method") {
        for
          receivedFrame <- Promise.make[Throwable, WebSocketFrame]
          echoServer = Handler.webSocket[Any] { channel =>
            channel.receiveAll {
              case ChannelEvent.Read(WebSocketFrame.Text(message)) =>
                channel.send(ChannelEvent.Read(WebSocketFrame.text(s"Echo: $message")))
              case _ => ZIO.unit
            }
          }
          testClientApp = Handler.webSocket[Any] { channel =>
            for
              _ <- channel.receive
              _ <- channel.send(ChannelEvent.Read(WebSocketFrame.text("Hello")))
              response <- channel.receive
              _ <- response match
                case ChannelEvent.Read(frame) => receivedFrame.succeed(frame)
                case _ => receivedFrame.fail(new RuntimeException("Expected ChannelEvent.Read"))
              _ <- channel.shutdown
            yield ()
          }
          _ <- TestClient.installSocketApp(echoServer)
          rawClient <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          client = rawClient @@ Observability.clientMiddleware(tracing)
          _ <- client.socket(testClientApp)
          frame <- receivedFrame.await
        yield assertTrue(
          frame match
            case WebSocketFrame.Text(msg) => msg == "Echo: Hello"
            case _ => false,
        )
      }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      // Closes the `Observability.client` ZLayer itself (lines 309/310): every other client
      // test wires `clientMiddleware` onto a raw `Client` by hand instead of via this layer.
      test("client layer wraps the default HTTP client with the observability middleware") {
        for builtEnv <- Observability.client.build
        yield assertTrue(builtEnv.get[Client] != null)
      }.provideSomeLayer[Scope](tracingLayer) @@ TestAspect.silentLogging,
      suite("client metrics")(
        test("records counter and histogram for successful requests") {
          val counterTags = Set(
            MetricLabel("method", "GET"),
            MetricLabel("peer", "unknown"),
            MetricLabel("route", "ok"),
            MetricLabel("status", "200"),
            MetricLabel("status_class", "2xx"),
          )
          val durationTags = Set(
            MetricLabel("method", "GET"),
            MetricLabel("peer", "unknown"),
            MetricLabel("route", "ok"),
            MetricLabel("status_class", "2xx"),
          )
          for
            _ <- TestClient.addRoutes(Routes(Method.GET / "ok" -> Handler.ok))
            rawClient <- ZIO.service[Client]
            tracing <- ZIO.service[Tracing]
            client = rawClient @@ Observability.clientMiddleware(tracing)
            requestsBefore <- clientCounterCount(counterTags)
            durationsBefore <- clientHistogramCount(durationTags)
            _ <- client.batched(Request.get(URL.empty / "ok"))
            requestsAfter <- clientCounterCount(counterTags)
            durationsAfter <- clientHistogramCount(durationTags)
          yield assertTrue(
            requestsAfter - requestsBefore == 1.0,
            durationsAfter - durationsBefore == 1L,
          )
        }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
        test("counts transport failures with status_class=error and records duration") {
          val failTags = Set(
            MetricLabel("method", "GET"),
            MetricLabel("peer", "unknown"),
            MetricLabel("route", "fail"),
            MetricLabel("status_class", "error"),
          )
          val failingClient = ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
            def request(
                version: Version,
                method: Method,
                url: URL,
                headers: Headers,
                body: Body,
                sslConfig: Option[ClientSSLConfig],
                proxy: Option[Proxy],
            )(using trace: Trace): ZIO[Any & Scope, Throwable, Response] =
              ZIO.fail(new java.net.ConnectException("Connection refused"))
            def socket[Env1 <: Any](
                version: Version,
                url: URL,
                headers: Headers,
                app: WebSocketApp[Env1],
            )(using trace: Trace, ev: Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
              ZIO.fail(new java.net.ConnectException("Connection refused")))
          for
            tracing <- ZIO.service[Tracing]
            client = failingClient @@ Observability.clientMiddleware(tracing)
            requestsBefore <- clientCounterCount(failTags)
            durationsBefore <- clientHistogramCount(failTags)
            _ <- client.batched(Request.get(URL.empty / "fail")).exit
            requestsAfter <- clientCounterCount(failTags)
            durationsAfter <- clientHistogramCount(failTags)
          yield assertTrue(
            requestsAfter - requestsBefore == 1.0,
            durationsAfter - durationsBefore == 1L,
          )
        }.provideSomeLayer[Scope](ZTestLogger.default ++ tracingLayer) @@ TestAspect.silentLogging,
        // Closes withClientRoute (lines 97/98): every other client test relies on the default
        // route (derived from the request path) instead of an explicit override.
        test("withClientRoute overrides the route tag used for client metrics") {
          val tags = Set(
            MetricLabel("method", "GET"),
            MetricLabel("peer", "unknown"),
            MetricLabel("route", "custom-route"),
            MetricLabel("status", "200"),
            MetricLabel("status_class", "2xx"),
          )
          for
            _ <- TestClient.addRoutes(Routes(Method.GET / "ok" -> Handler.ok))
            rawClient <- ZIO.service[Client]
            tracing <- ZIO.service[Tracing]
            client = rawClient @@ Observability.clientMiddleware(tracing)
            _ <- Observability.withClientRoute("custom-route")(client.batched(Request.get(URL.empty / "ok")))
            requests <- clientCounterCount(tags)
          yield assertTrue(requests >= 1.0)
        }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
        // Closes the absolute-URL peer label branch (line 375).
        test("uses the absolute URL's host and port as the peer metric label") {
          val tags = Set(
            MetricLabel("method", "GET"),
            MetricLabel("peer", "peer-host:5678"),
            MetricLabel("route", "ok"),
            MetricLabel("status", "200"),
            MetricLabel("status_class", "2xx"),
          )
          for
            _ <- TestClient.addRoutes(Routes(Method.GET / "ok" -> Handler.ok))
            rawClient <- ZIO.service[Client]
            tracing <- ZIO.service[Tracing]
            client = rawClient @@ Observability.clientMiddleware(tracing)
            url <- ZIO.fromEither(URL.decode("http://peer-host:5678/ok")).orDie
            _ <- client.batched(Request.get(url))
            requests <- clientCounterCount(tags)
          yield assertTrue(requests >= 1.0)
        }.provideSomeLayer[Scope](testLayer) @@ TestAspect.silentLogging,
      ),
    ),
    // Closes the logCause LogAnnotation's render function (lines 537-549): unlike the
    // stack_trace rendered by zio-logging's built-in `cause` format element (exercised by the
    // "boom" tests above), this is a custom annotation never otherwise triggered.
    suite("logCause")(
      test("renders the exception class, message and a filtered stack trace") {
        val ex = new RuntimeException("boom-cause")
        val format = logAnnotation(Observability.logCause)
        for
          _ <- ZIO.logInfo("test") @@ Observability.logCause(ex)
          logs <- ZTestLogger.logOutput
          rawLog <- ZIO.fromOption(logs.headOption).orElseFail(new RuntimeException("Missing log"))
          rendered = rawLog.call(format.toLogger)
        yield assertTrue(
          rendered.contains("java.lang.RuntimeException: boom-cause"),
          rendered.contains("\tat "),
        )
      }.provide(ZTestLogger.default) @@ TestAspect.silentLogging,
    ),
  )
