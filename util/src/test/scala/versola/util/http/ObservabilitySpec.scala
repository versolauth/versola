package versola.util.http

import io.opentelemetry.api
import zio.*
import zio.http.*
import zio.json.*
import zio.logging.LogFormat.{cause, label, level, line, logAnnotation, quoted, space}
import zio.logging.LogFilter
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

  private val renderedLogFormat =
    List(
      label("level", level),
      label("message", quoted(line)),
      (space + label("stack_trace", cause)).filter(LogFilter.causeNonEmpty),
      logAnnotation(Observability.receiveHttp),
    ).reduce(_ |-| _)

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val testLayer =
    TestClient.layer ++ ZTestLogger.default

  private val routes =
    Observability.middleware(
      Observability.handleErrors(
          Routes(
            Method.GET / "ok" -> Handler.fromResponse(Response.text("ok")),
            Method.GET / "boom" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(new RuntimeException("boom"))),
          )
      ),
    )

  def spec = suite("Observability")(
    test("logs a single info entry for successful requests") {
      for
        env <- (tracingLayer ++ ZLayer.succeed(HttpObservabilityConfig.default)).build
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
        env <- (tracingLayer ++ ZLayer.succeed(HttpObservabilityConfig.default)).build
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
  )
