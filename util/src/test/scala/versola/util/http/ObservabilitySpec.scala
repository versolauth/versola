package versola.util.http

import io.opentelemetry.api
import zio.*
import zio.http.*
import zio.json.*
import zio.logging.LogFilter
import zio.logging.LogFormat.{cause, label, level, line, logAnnotation, quoted, space}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object ObservabilitySpec extends ZIOSpecDefault:
  private case class LoggedRequest(path: String) derives JsonDecoder
  private case class LoggedResponse(code: Int) derives JsonDecoder
  private case class LoggedHttp(request: LoggedRequest, response: LoggedResponse) derives JsonDecoder
  private case class LoggedEntry(
      level: String,
      message: String,
      http: LoggedHttp,
      stack_trace: Option[String] = None,
  ) derives JsonDecoder

  private case class ClientLoggedRequest(
      path: String,
      queryParams: List[String],
      headers: List[String],
      body: Option[String],
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
    ).reduce(_ |-| _)

  private val clientLogFormat =
    List(
      label("level", level),
      label("message", quoted(line)),
      logAnnotation(Observability.sendHttp),
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
        ),
      ),
    )

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
    ),
  )
