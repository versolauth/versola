package versola.util.http

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio.*
import zio.http.*
import zio.http.codec.HttpCodecError
import zio.json.*
import zio.logging.{LogAnnotation, logContext}
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.metrics.{Metric, MetricLabel}
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.time.Instant
import scala.collection.mutable

object Observability:
  val receiveHttp = LogAnnotation[ReceiveHttpLog]("http", (_, r) => r, _.toJson)
  val sendHttp = LogAnnotation[SendHttpLog]("http", (_, r) => r, _.toJson)

  /** Authentication context of the request being handled, rendered under the `auth` key of
    * every log line emitted after it is filled in. Defined generically here since `util` is
    * shared by services that don't know about the `auth` module's own model types.
    */
  val auth = LogAnnotation[AuthDetails]("auth", (_, r) => r, _.toJson)

  /** Outcome of a request that ended in a user-facing or server error, rendered under the
    * `error` key of the log line that surfaces it (e.g. the `receive-http` line of the
    * request/redirect that carried the failure). */
  val error = LogAnnotation[ErrorDetails]("error", (_, r) => r, _.toJson)

  val cause = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(Option.empty[Cause[Any]])
  }

  /** Fills in the auth context of the request currently being handled. Details are discovered
    * mid-flow (e.g. only once a conversation is created or an existing one is looked up), hence
    * an imperative update of the annotation from deep in the call stack rather than a scoped
    * `@@` annotation. [[middleware]] restores the context once the request completes, so the
    * details never leak into a subsequent request.
    */
  def updateAuth(f: AuthDetails => AuthDetails): UIO[Unit] =
    logContext.update(context => context.annotate(auth, f(context.get(auth).getOrElse(AuthDetails.empty))))

  def setAuthId(id: String): UIO[Unit] = updateAuth(_.copy(id = Some(id)))

  /** Sets `auth.id` and `auth.client_id` together, since every place that discovers one
    * (a fresh authId or a signed conversation cookie) already has the other at hand. */
  def setAuth(authId: String, clientId: String): UIO[Unit] =
    updateAuth(_.copy(id = Some(authId), clientId = Some(clientId)))

  def setPriorSessionId(id: String): UIO[Unit] = updateAuth(_.copy(priorSessionId = Some(id)))

  def setSessionId(id: String): UIO[Unit] = updateAuth(_.copy(sessionId = Some(id)))

  def setClientId(id: String): UIO[Unit] = updateAuth(_.copy(clientId = Some(id)))

  /** Conversation step (`credential`, `otp`, `password`, ...) the request is currently on. */
  def setStep(step: String): UIO[Unit] = updateAuth(_.copy(step = Some(step)))

  def setUserId(id: String): UIO[Unit] = updateAuth(_.copy(userId = Some(id)))

  def setUserAgentId(id: String): UIO[Unit] = updateAuth(_.copy(userAgentId = Some(id)))

  def setIp(ip: String): UIO[Unit] = updateAuth(_.copy(ip = Some(ip)))

  /** `jti` of the access token issued by the request. */
  def setToken(jti: String): UIO[Unit] = updateAuth(_.copy(token = Some(jti)))

  /** Refresh tokens are bearer credentials, so only a prefix long enough to correlate log
    * lines is kept. */
  def setRefreshToken(refreshToken: String): UIO[Unit] =
    updateAuth(_.copy(refreshToken = Some(refreshToken.take(RefreshTokenPrefixLength))))

  private val RefreshTokenPrefixLength = 9

  /** Refresh token consumed by a `refresh_token` grant, i.e. the raw value exchanged for a
    * new one. A bearer credential like [[setRefreshToken]], so only a prefix is kept. */
  def setPreviousRefreshToken(refreshToken: String): UIO[Unit] =
    updateAuth(_.copy(previousRefreshToken = Some(refreshToken.take(RefreshTokenPrefixLength))))

  /** Records the outcome of a request that ended in an error, under the `error` key. `code`
    * should come from a stable, closed vocabulary (e.g. `stepErrorKey`, `ErrorCode`) so it can
    * be used for alerting/metrics without risking unbounded cardinality. */
  def setError(code: String, description: Option[String] = None): UIO[Unit] =
    logContext.update(_.annotate(error, ErrorDetails(code, description)))

  /** Like [[setError]], but a no-op if the error context was already filled in earlier in
    * this request. Lets a service-layer detector record a more specific description than
    * the generic default an endpoint's `catchAll` falls back to for the same error code,
    * without that fallback clobbering it once the failure reaches the controller boundary. */
  def setErrorIfAbsent(code: String, description: Option[String] = None): UIO[Unit] =
    logContext.update: context =>
      context.get(error) match
        case Some(_) => context
        case None    => context.annotate(error, ErrorDetails(code, description))

  val clientLogging: FiberRef[HttpObservabilityConfig.Client] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(HttpObservabilityConfig.Client.default)
  }

  val clientRoute: FiberRef[Option[String]] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(Option.empty[String])
  }

  def withClientRoute[R, E, A](route: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    clientRoute.locally(Some(route))(zio)

  /** Query-string-shaped suffix appended to the `route` label of the current request's
    * `http_server_requests_total`/`http_server_request_duration_seconds` metrics, e.g.
    * `grant_type=client_credentials` turns the `POST /token` route into
    * `POST /token?grant_type=client_credentials` so grant types are distinguishable in metrics
    * despite sharing a single route. Discovered mid-flight (e.g. once the request body is
    * parsed), hence an imperative update rather than a scoped `@@` annotation. [[middleware]]
    * resets it per request so it never leaks into a subsequent request.
    */
  val routeLabel: FiberRef[Option[String]] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(Option.empty[String])
  }

  def setRouteLabel(key: String, value: String): UIO[Unit] =
    routeLabel.set(Some(s"$key=$value"))

  /** Replaces the route path of the current request's
    * `http_server_requests_total`/`http_server_request_duration_seconds` metrics, which
    * defaults to the matched route's pattern. Used where a single pattern fans out into
    * several routes known only at request time, e.g. edge's `/resources/{resourceId}/...`
    * proxy resolving to a registered endpoint template. The value must come from
    * configuration rather than the request itself, otherwise metric cardinality becomes
    * unbounded. [[middleware]] resets it per request so it never leaks into a subsequent
    * one.
    */
  val routePath: FiberRef[Option[String]] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(Option.empty[String])
  }

  def setRoutePath(path: String): UIO[Unit] =
    routePath.set(Some(path))

  val serverLogging: FiberRef[HttpObservabilityConfig.Server] = zio.Unsafe.unsafe { case given zio.Unsafe =>
    FiberRef.unsafe.make(HttpObservabilityConfig.Server.default)
  }

  def withServerLogging[R, E, A](
      f: HttpObservabilityConfig.Server => HttpObservabilityConfig.Server,
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    serverLogging.set(f(HttpObservabilityConfig.Server.default)) *> zio

  val durationBoundaries: Boundaries =
    Boundaries.fromChunk(Chunk(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 7.5, 10.0, 15.0, 20.0, 25.0, 30.0))

  private val requestsCount =
    Metric.counter("http_server_requests_total")

  private val requestDuration =
    Metric.histogram("http_server_request_duration_seconds", durationBoundaries)

  private val activeRequests =
    Metric.gauge("http_server_active_requests")

  val clientDurationBoundaries: Boundaries =
    Boundaries.fromChunk(Chunk(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30))

  private val clientRequestsCount =
    Metric.counter("http_client_requests_total")

  private val clientRequestDuration =
    Metric.histogram("http_client_request_duration_seconds", clientDurationBoundaries)

  /** Masks an `Authorization` header value while preserving its scheme, e.g. `Bearer secret`
    * becomes `Bearer ***` and `Basic secret` becomes `Basic ***`, so logs stay useful for
    * distinguishing auth schemes without leaking credentials. Falls back to `***` when no
    * scheme token is present. */
  private[http] def maskAuthorization(value: String): String =
    value.indexOf(' ') match
      case -1 => "***"
      case i => s"${value.substring(0, i)} ***"

  def handleErrors[Env](routes: Routes[Env, Throwable]): Routes[Env, Nothing] =
    routes.handleErrorZIO {
      case Unauthorized => ZIO.succeed(Response.unauthorized)
      case Forbidden => ZIO.succeed(Response.forbidden)
      case ex: BadRequest => ZIO.succeed(Response.text(ex.message).status(Status.BadRequest))
      case ex: HttpCodecError => ZIO.succeed(Response.text(ex.message).status(Status.BadRequest))
      case ex: Throwable => Observability.cause.set(Some(Cause.fail(ex))).as(Response.internalServerError)
    }

  private def toLog(request: Request, masking: HttpObservabilityConfig.Server): UIO[HttpRequestLog] = {
    val query = request.url.queryParams.map
      .collect { case (k, vs) if masking.logQuery.contains(k) => s"$k=${vs.mkString(",")}" }
      .toSeq

    val headers = request.headers
      .collect {
        case h if h.headerName == Header.Authorization.name => s"${h.headerName}=${maskAuthorization(h.renderedValue)}"
        case h if masking.logRequestHeaders.contains(h.headerName) => s"${h.headerName}=${h.renderedValue}"
      }.toSeq

    val cookies = request.cookies.map(_.name).toSeq
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

  /** Instruments every route with its own pattern (e.g. `/resources/{resourceId}/...`)
    * rather than the concrete request path, so path parameters cannot blow up metric
    * cardinality or span names. Handlers may narrow the route path to another bounded
    * value with [[setRoutePath]].
    */
  val middleware: Middleware[Tracing] = new Middleware[Tracing]:
    def apply[Env1 <: Tracing, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      Routes.fromIterable(routes.routes.map(route => route.transform(instrument(route.routePattern.pathCodec.render))))

    private def instrument[Env1 <: Tracing](
        pattern: String,
    )(handler: Handler[Env1, Response, Request, Response]): Handler[Env1, Response, Request, Response] =
      Handler.scoped[Env1]:
        Handler.fromFunctionZIO[Request]: request =>
          ZIO.serviceWithZIO[Tracing]: tracing =>
            // the log context is restored once the request completes, so auth details set
            // mid-flow annotate every log line of this request and no other
            logContext.locallyWith(identity)(
              routeLabel.locally(None)(
              routePath.locally(None)(
              for
                startTime <- Clock.instant
                now <- Clock.nanoTime
                baseTags = Set(
                  MetricLabel("method", request.method.name),
                  MetricLabel("route", pattern),
                )
                response <- activeRequests.tagged(baseTags).increment
                  .zipRight(handler(request))
                  .ensuring(activeRequests.tagged(baseTags).decrement)
                masking <- serverLogging.get
                (requestLog, responseLog) <- toLog(request, masking) <&> toLog(request, response, masking)
                after <- Clock.nanoTime
                status = response.status.code
                statusClass = s"${status / 100}xx"
                label <- routeLabel.get
                path <- routePath.get.map(_.getOrElse(pattern))
                route = label.fold(path)(l => s"$path?$l")
                tags = Set(
                  MetricLabel("method", request.method.name),
                  MetricLabel("route", route),
                )
                _ <- requestsCount
                  .tagged(tags + MetricLabel("status", status.toString) + MetricLabel("status_class", statusClass))
                  .increment
                _ <- requestDuration
                  .tagged(tags + MetricLabel("status_class", statusClass))
                  .update((after - now) / 1e9)
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
              yield response,
              ),
              ),
            ) @@ tracing.aspects.extractSpan(
              TraceContextPropagator.default,
              IncomingContextCarrier.default(
                mutable.Map.from(request.headers.map(h => h.headerName -> h.renderedValue)),
              ),
              s"${request.method.name} $pattern",
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
                  route <- clientRoute.get.map(_.getOrElse(url.path.encode.stripPrefix("/")))
                  _ <- clientLog(method, url, route, tracedHeaders, body, startTime, duration, masking, exit)
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
      route: String,
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
      case h if h.headerName == Header.Authorization.name => s"${h.headerName}=${maskAuthorization(h.renderedValue)}"
      case h if masking.logRequestHeaders.contains(h.headerName) => s"${h.headerName}=${h.renderedValue}"
    .toSeq
    val baseUri = url.kind match
      case loc: URL.Location.Absolute => s"${loc.scheme.encode}://${loc.host}:${loc.port}"
      case URL.Location.Relative => ""
    val peer = url.kind match
      case loc: URL.Location.Absolute => s"${loc.host}:${loc.port}"
      case URL.Location.Relative => "unknown"
    val bodyEffect =
      if masking.logRequestBody && body.isComplete then body.asString.asSome.orElseSucceed(None)
      else ZIO.none

    val loggerName = logging.loggerName("versola.http.HttpClient")
    val seconds = duration.toNanos / 1e9

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
          val failTags = Set(
            MetricLabel("method", method.name),
            MetricLabel("peer", peer),
            MetricLabel("route", route),
            MetricLabel("status_class", "error"),
          )
          val responseLog = HttpClientResponseLog(code = 500, body = None, headers = Seq.empty)
          val log = SendHttpLog(requestLog, responseLog, startTime, elapsedMillis = duration.toMillis)
          clientRequestsCount.tagged(failTags).increment *>
            clientRequestDuration.tagged(failTags).update(seconds) *>
            (ZIO.logErrorCause("send-http", cause) @@ sendHttp(log) @@ loggerName)
        case Exit.Success(response) =>
          val status = response.status.code
          val statusClass = s"${status / 100}xx"
          val tags = Set(
            MetricLabel("method", method.name),
            MetricLabel("peer", peer),
            MetricLabel("route", route),
            MetricLabel("status", status.toString),
            MetricLabel("status_class", statusClass),
          )
          val durationTags = Set(
            MetricLabel("method", method.name),
            MetricLabel("peer", peer),
            MetricLabel("route", route),
            MetricLabel("status_class", statusClass),
          )
          val bodyEffect =
            if !masking.logResponseBody then ZIO.none
            else if response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html) then ZIO.some("<html>")
            else if response.body.isComplete then response.body.asString.asSome.orElseSucceed(None)
            else ZIO.none
          bodyEffect.flatMap: body =>
            val responseLog = HttpClientResponseLog(
              code = status,
              body = body,
              headers = response.headers.collect {
                case h if masking.logResponseHeaders.contains(h.headerName) =>
                  s"${h.headerName}=${h.renderedValue}"
              }.toSeq,
            )
            val log = SendHttpLog(requestLog, responseLog, startTime, elapsedMillis = duration.toMillis)
            clientRequestsCount.tagged(tags).increment *>
              clientRequestDuration.tagged(durationTags).update(seconds) *>
              (
                if response.status.isServerError then
                  ZIO.logError("send-http") @@ sendHttp(log) @@ loggerName
                else
                  ZIO.logInfo("send-http") @@ sendHttp(log) @@ loggerName
              )
    }

  @jsonMemberNames(SnakeCase)
  case class HttpClientRequestLog(
      method: String,
      baseUri: String,
      path: String,
      queryParams: Seq[String],
      body: Option[String],
      headers: Seq[String],
  ) derives JsonEncoder

  @jsonMemberNames(SnakeCase)
  case class HttpClientResponseLog(
      code: Int,
      body: Option[String],
      headers: Seq[String],
  ) derives JsonEncoder

  @jsonMemberNames(SnakeCase)
  case class HttpRequestLog(
      method: String,
      baseUri: String,
      path: String,
      queryParams: Seq[String],
      body: Option[String],
      headers: Seq[String],
      cookies: Seq[String],
  ) derives JsonEncoder

  @jsonMemberNames(SnakeCase)
  case class HttpResponseLog(
      code: Int,
      body: Option[String],
      headers: Seq[String],
  ) derives JsonEncoder

  @jsonMemberNames(SnakeCase)
  case class ReceiveHttpLog(
      request: HttpRequestLog,
      response: HttpResponseLog,
      startTime: Instant,
      elapsedMillis: Long,
  ) derives JsonEncoder

  @jsonMemberNames(SnakeCase)
  case class SendHttpLog(
      request: HttpClientRequestLog,
      response: HttpClientResponseLog,
      startTime: Instant,
      elapsedMillis: Long,
  ) derives JsonEncoder

  @jsonMemberNames(SnakeCase)
  case class AuthDetails(
      id: Option[String] = None,
      /** `sid` of the session the request arrived with. */
      priorSessionId: Option[String] = None,
      /** `sid` of the session issued by the request, when it establishes a new one. */
      sessionId: Option[String] = None,
      clientId: Option[String] = None,
      /** Conversation step (`credential`, `otp`, `password`, ...) the request is currently on. */
      step: Option[String] = None,
      userId: Option[String] = None,
      /** Id of the device/browser (`SSO_USER_AGENT_ID` cookie), stable across sessions. */
      userAgentId: Option[String] = None,
      ip: Option[String] = None,
      /** `jti` of the access token issued by the request. */
      token: Option[String] = None,
      /** Prefix of the refresh token issued by the request. */
      refreshToken: Option[String] = None,
      /** Prefix of the refresh token consumed by a `refresh_token` grant. */
      previousRefreshToken: Option[String] = None,
  ) derives JsonEncoder

  object AuthDetails:
    val empty: AuthDetails = AuthDetails()

  /** Outcome of a request that ended in an error. `code` is a stable machine-readable key
    * (e.g. `otp_wrong`, `rate_limit_exceeded`, `service_unavailable`); `description` is
    * optional free text for context that isn't captured by the code alone. */
  @jsonMemberNames(SnakeCase)
  case class ErrorDetails(
      code: String,
      description: Option[String] = None,
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
