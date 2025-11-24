package versola.http

import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.http.*
import zio.json.*
import zio.logging.LogAnnotation
import zio.telemetry.opentelemetry.tracing.Tracing

import java.io.{PrintWriter, StringWriter}
import java.time.Instant

object Observability:
  val receiveHttp = LogAnnotation[ReceiveHttpLog]("http", (_, r) => r, _.toJson)

  private def toLog(request: Request, config: Option[HttpObservabilityConfig.Masking]): UIO[HttpRequestLog] = {
    val query = request.url.queryParams.map
      .collect { case (k, values) => s"$k=${values.mkString(",")}" }
      .toSeq

    val headers = request.headers
      .map {
        case h if h.headerName == Header.Authorization.name => s"${h.headerName}=Bearer ***"
        case h => s"${h.headerName}=${h.renderedValue}"
      }.toSeq

    val cookies = request.cookies.map(cookie => s"${cookie.name}=${cookie.content}").toSeq
    for
      logRequestBody = config.fold(true)(_.logRequestBody)
      body <-
        if logRequestBody then
          request.body.asString.asSome.orElseSucceed(None)
        else
          ZIO.none

      log = HttpRequestLog(
        method = request.method.name,
        baseUri = request.url.kind match {
          case location: URL.Location.Absolute => s"${location.scheme.encode}://${location.host}:${location.port}"
          case URL.Location.Relative => "http://"
        },
        path = request.url.path.encode,
        moreQueryParams = query,
        body = body,
        moreHeaders = headers,
        moreCookies = cookies,
      )
    yield log
  }

  private def toLog(request: Request, response: Response, config: Option[HttpObservabilityConfig.Masking]): UIO[HttpResponseLog] = {
    for
      logResponseBody = config.fold(true)(_.logResponseBody)
      body <-
        if !logResponseBody then
          ZIO.none
        else if response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html) then
          ZIO.some("<html>")
        else
          response.body.asString.asSome.orElseSucceed(None)
    yield HttpResponseLog(
      code = response.status.code,
      body = body,
      moreHeaders = response.headers.map(h => s"${h.headerName}=${h.renderedValue}").toSeq,
    )
  }

  val middleware: Middleware[Tracing & HttpObservabilityConfig] = new Middleware[Tracing & HttpObservabilityConfig]:
    def apply[Env1 <: Tracing & HttpObservabilityConfig, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      routes.transform: handler =>
        Handler.scoped[Env1]:
          Handler.fromFunctionZIO: request =>
            ZIO.serviceWithZIO[Tracing]: tracing =>
              (
                for
                  now <- Clock.nanoTime
                  response <- handler(request)
                  config <- ZIO.service[HttpObservabilityConfig]
                  pathConfig = config.masking.get(request.path)
                  (requestLog, responseLog) <- toLog(request, pathConfig) <&> toLog(request, response, pathConfig)
                  after <- Clock.nanoTime
                  log = receiveHttp(
                    ReceiveHttpLog(
                      request = requestLog,
                      response = responseLog,
                      startTime = Instant.ofEpochMilli(now / 1000000),
                      elapsedMillis = (after - now) / 1000000,
                    ),
                  )
                  loggerName = logging.loggerName("versola.http.HttpServer")
                  //ex <- Controller.exceptions.get
                  //error = ex.map(logCause(_)).getOrElse(ZIOAspect.identity)
                  _ <-
                    if response.status.isServerError then
                      ZIO.logError("receive-http") @@ log @@ loggerName// @@ error
                    else
                      ZIO.logInfo("receive-http") @@ log @@ loggerName
                  //_ <- Controller.exceptions.set(None).when(ex.nonEmpty)
                yield response
              ) @@ tracing.aspects.root(s"${request.method.name} ${request.path.encode}", SpanKind.SERVER)

  case class HttpRequestLog(
      method: String,
      baseUri: String,
      path: String,
      // queryParams: Map[String, String], Пока что нет смысла в индексированных параметрах
      moreQueryParams: Seq[String],
      body: Option[String],
      // headers: Map[String, String],
      moreHeaders: Seq[String],
      // cookies: Map[String, String],
      moreCookies: Seq[String],
  ) derives JsonEncoder

  case class HttpResponseLog(
      code: Int,
      body: Option[String],
      // headers: Map[String, String],
      moreHeaders: Seq[String],
  ) derives JsonEncoder

  case class ReceiveHttpLog(
      request: HttpRequestLog,
      response: HttpResponseLog,
      startTime: Instant,
      elapsedMillis: Long,
  ) derives JsonEncoder

  val logCause = zio.logging.LogAnnotation[Throwable](
    "stack_trace",
    (_, r) => r,
    ex => {
      val str = StringBuilder()
      str.append(ex.getClass.getName)
      str.append(": ")
      str.append(ex.getLocalizedMessage)
      str.append("\n")
      ex.getStackTrace.foreach { el =>
        val elStr = el.toString
        if !elStr.startsWith("zio.internal.FiberRuntime") then
          str.append("\tat ")
          str.append(elStr)
          str.append("\n")
      }
      str.toString()
  })

