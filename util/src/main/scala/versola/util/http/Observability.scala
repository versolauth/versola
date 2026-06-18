package versola.util.http

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio.*
import zio.http.*
import zio.json.*
import zio.logging.LogAnnotation
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.time.Instant
import scala.collection.mutable

object Observability:
  val receiveHttp = LogAnnotation[ReceiveHttpLog]("http", (_, r) => r, _.toJson)
  val sendHttp = LogAnnotation[SendHttpLog]("http", (_, r) => r, _.toJson)

  val cause = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(Option.empty[Cause[Any]])
  }

  val clientLogging: FiberRef[HttpObservabilityConfig.Client] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(HttpObservabilityConfig.Client.default)
  }

  val serverLogging: FiberRef[HttpObservabilityConfig.Server] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(HttpObservabilityConfig.Server.default)
  }

  def withServerLogging[R, E, A](
      f: HttpObservabilityConfig.Server => HttpObservabilityConfig.Server,
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    serverLogging.set(f(HttpObservabilityConfig.Server.default)) *> zio

  def handleErrors[Env](routes: Routes[Env, Throwable]): Routes[Env, Nothing] =
    routes.handleErrorZIO {
      case Unauthorized => ZIO.succeed(Response.unauthorized)
      case ex: Throwable => Observability.cause.set(Some(Cause.fail(ex))).as(Response.internalServerError)
    }

  private def toLog(request: Request, masking: HttpObservabilityConfig.Server): UIO[HttpRequestLog] = {
    val query = request.url.queryParams.map
      .collect { case (k, vs) if masking.logQuery.contains(k) => s"$k=${vs.mkString(",")}" }
      .toSeq

    val headers = request.headers
      .collect {
        case h if h.headerName == Header.Authorization.name => s"${h.headerName}=Bearer ***"
        case h if masking.logRequestHeaders.contains(h.headerName) => s"${h.headerName}=${h.renderedValue}"
      }.toSeq

    val cookies = request.cookies.map(cookie => s"${cookie.name}=${cookie.content}").toSeq
    for
      body <-
        if masking.logRequestBody then
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
        queryParams = query,
        body = body,
        headers = headers,
        cookies = cookies,
      )
    yield log
  }

  private def toLog(request: Request, response: Response, masking: HttpObservabilityConfig.Server): UIO[HttpResponseLog] = {
    for
      body <-
        if !masking.logResponseBody then
          ZIO.none
        else if response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html) then
          ZIO.some("<html>")
        else
          response.body.asString.asSome.orElseSucceed(None)
    yield HttpResponseLog(
      code = response.status.code,
      body = body,
      headers = response.headers.collect {
        case h if masking.logResponseHeaders.contains(h.headerName) =>
          s"${h.headerName}=${h.renderedValue}"
      }.toSeq,
    )
  }

  val middleware: Middleware[Tracing] = new Middleware[Tracing]:
    def apply[Env1 <: Tracing, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      routes
        .transform: handler =>
          Handler.scoped[Env1]:
            Handler.fromFunctionZIO[Request]: request =>
              ZIO.serviceWithZIO[Tracing]: tracing =>
                (
                  for
                    startTime <- Clock.instant
                    now <- Clock.nanoTime
                    response <- handler(request)
                    masking <- serverLogging.get
                    (requestLog, responseLog) <- toLog(request, masking) <&> toLog(request, response, masking)
                    after <- Clock.nanoTime
                    log = receiveHttp(
                      ReceiveHttpLog(
                        request = requestLog,
                        response = responseLog,
                        startTime = startTime,
                        elapsedMillis = (after - now) / 1000000,
                      ),
                    )
                    loggerName = logging.loggerName("versola.http.HttpServer")
                    cause <- cause.get
                    _ <- cause match
                      case Some(cause) =>
                        ZIO.logErrorCause("receive-http", cause) @@ log @@ loggerName
                      case None =>
                        ZIO.logInfo("receive-http") @@ log @@ loggerName
                    _ <- Observability.cause.set(None)
                  yield response
                ) @@ tracing.aspects.extractSpan(
                  TraceContextPropagator.default,
                  IncomingContextCarrier.default(
                    mutable.Map.from(request.headers.map(h => h.headerName -> h.renderedValue)),
                  ),
                  s"${request.method.name} ${request.path.encode}",
                  SpanKind.SERVER,
                )

  val client: ZLayer[Tracing, Throwable, Client] =
    (Client.default ++ ZLayer.service[Tracing]).map: env =>
      ZEnvironment(env.get[Client] @@ clientMiddleware(env.get[Tracing]))

  def clientMiddleware(tracing: Tracing): ZClientAspect[Nothing, Any, Nothing, Body, Nothing, Any, Nothing, Response] =
    new ZClientAspect[Nothing, Any, Nothing, Body, Nothing, Any, Nothing, Response]:
      def apply[ReqEnv, Env <: Any, In <: Body, Err, Out <: Response](
          client: ZClient[Env, ReqEnv, In, Err, Out],
      ): ZClient[Env, ReqEnv, In, Err, Out] =
        client.transform(
          client.bodyEncoder,
          client.bodyDecoder,
          new ZClient.Driver[Env, ReqEnv, Err]:
            def request(
                version: Version,
                method: Method,
                url: URL,
                headers: Headers,
                body: Body,
                sslConfig: Option[ClientSSLConfig],
                proxy: Option[Proxy],
            )(using trace: Trace): ZIO[Env & ReqEnv, Err, Response] =
              tracing.span(s"${method.name} ${url.path.encode}", SpanKind.CLIENT):
                for
                  carrier <- ZIO.succeed(OutgoingContextCarrier.default())
                  _ <- tracing.injectSpan(TraceContextPropagator.default, carrier)
                  tracedHeaders = headers ++ Headers.fromIterable(carrier.kernel.map((k, v) => Header.Custom(k, v)))
                  startTime <- Clock.instant
                  result <- client.driver.request(version, method, url, tracedHeaders, body, sslConfig, proxy)
                    .sandbox.exit.timed
                  (duration, exit) = result
                  masking <- clientLogging.get
                  _ <- clientLog(method, url, tracedHeaders, body, startTime, duration, masking, exit)
                  response <- exit.unsandbox
                yield response

            def socket[Env1 <: Env](version: Version, url: URL, headers: Headers, app: WebSocketApp[Env1])(
                using
                trace: Trace,
                ev: ReqEnv =:= Scope,
            ): ZIO[Env1 & ReqEnv, Err, Response] =
              client.driver.socket(version, url, headers, app),
        )

  private def clientLog(
      method: Method,
      url: URL,
      headers: Headers,
      body: Body,
      startTime: Instant,
      duration: Duration,
      masking: HttpObservabilityConfig.Client,
      exit: Exit[Cause[Any], Response],
  ): UIO[Unit] =
    val query = url.queryParams.map
      .collect { case (k, vs) if masking.logQuery.contains(k) => s"$k=${vs.mkString(",")}" }
      .toSeq
    val maskedHeaders = headers.collect:
      case h if h.headerName == Header.Authorization.name => s"${h.headerName}=Bearer ***"
      case h if masking.logRequestHeaders.contains(h.headerName) => s"${h.headerName}=${h.renderedValue}"
    .toSeq
    val baseUri = url.kind match
      case loc: URL.Location.Absolute => s"${loc.scheme.encode}://${loc.host}:${loc.port}"
      case URL.Location.Relative => ""
    val bodyEffect =
      if masking.logRequestBody && body.isComplete then body.asString.asSome.orElseSucceed(None)
      else ZIO.none

    val loggerName = logging.loggerName("versola.http.HttpClient")

    bodyEffect.flatMap { body =>
      val requestLog = HttpClientRequestLog(
        method = method.name,
        baseUri = baseUri,
        path = url.path.encode,
        queryParams = query,
        body = body,
        headers = maskedHeaders,
      )

      exit match
        case Exit.Failure(cause) =>
          val responseLog = HttpClientResponseLog(code = 500, body = None, headers = Seq.empty)
          val log = SendHttpLog(requestLog, responseLog, startTime, elapsedMillis = duration.toMillis)
          ZIO.logErrorCause("send-http", cause) @@ sendHttp(log) @@ loggerName
        case Exit.Success(response) =>
          val bodyEffect =
            if !masking.logResponseBody then ZIO.none
            else if response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html) then ZIO.some("<html>")
            else if response.body.isComplete then response.body.asString.asSome.orElseSucceed(None)
            else ZIO.none
          bodyEffect.flatMap: body =>
            val responseLog = HttpClientResponseLog(
              code = response.status.code,
              body = body,
              headers = response.headers.collect {
                case h if masking.logResponseHeaders.contains(h.headerName) =>
                  s"${h.headerName}=${h.renderedValue}"
              }.toSeq,
            )
            val log = SendHttpLog(requestLog, responseLog, startTime, elapsedMillis = duration.toMillis)
            if response.status.isServerError then
              ZIO.logError("send-http") @@ sendHttp(log) @@ loggerName
            else
              ZIO.logInfo("send-http") @@ sendHttp(log) @@ loggerName
    }

  case class HttpClientRequestLog(
      method: String,
      baseUri: String,
      path: String,
      queryParams: Seq[String],
      body: Option[String],
      headers: Seq[String],
  ) derives JsonEncoder

  case class HttpClientResponseLog(
      code: Int,
      body: Option[String],
      headers: Seq[String],
  ) derives JsonEncoder

  case class HttpRequestLog(
      method: String,
      baseUri: String,
      path: String,
      queryParams: Seq[String],
      body: Option[String],
      headers: Seq[String],
      cookies: Seq[String],
  ) derives JsonEncoder

  case class HttpResponseLog(
      code: Int,
      body: Option[String],
      headers: Seq[String],
  ) derives JsonEncoder

  case class ReceiveHttpLog(
      request: HttpRequestLog,
      response: HttpResponseLog,
      startTime: Instant,
      elapsedMillis: Long,
  ) derives JsonEncoder

  case class SendHttpLog(
      request: HttpClientRequestLog,
      response: HttpClientResponseLog,
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
    },
  )
