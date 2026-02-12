package versola

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes
import versola.edge.{EdgeConfig, EdgeCredentialsService, EdgeSessionRepository, EdgeSessionService}
import versola.util.*
import zio.http.Client
import versola.util.http.{HttpObservabilityConfig, MetricsService, Observability, ReadinessService}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.*
import zio.http.*
import zio.http.Server.RequestStreaming
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, LoggerNameExtractor}
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}

abstract class EdgeApp extends ZIOApp:
  private val serviceName = "edge"

  override type Environment =
    ContextStorage & EdgeConfig & ConfigProvider & LogFormats & api.OpenTelemetry & Tracing

  import versola.util.http.HttpObservabilityConfig.Masking

  val config = EdgeApp.Config.default
    .withPort(8081)
    .withDiagnosticsPort(9346)
    .withRequestStreaming(RequestStreaming.Disabled(1024 * 500))

  type Repositories =
    EdgeSessionRepository

  type Services =
    SecureRandom &
      EdgeSessionService &
      EdgeCredentialsService &
      SecurityService &
      Client

  def routes: Routes[Services & EdgeConfig & Tracing, Nothing]

  def dependencies: RLayer[zio.Scope & ConfigProvider, Repositories]

  val services: ZLayer[ConfigProvider & EdgeConfig & Tracing, Throwable, Services] =
    (zio.Scope.default >+> dependencies) >>>
      ZLayer.makeSome[zio.Scope & EdgeConfig & Repositories & Tracing, Services](
        SecureRandom.live,
        EdgeSessionService.live,
        EdgeCredentialsService.live,
        SecurityService.live,
        Client.default,
      )

  override val bootstrap: ZLayer[Any, Any, Environment] =
    EdgeApp.config >+>
      OpenTelemetry.contextZIO >+>
      ZioLogging.logFormats >+>
      EdgeApp.jsonLoggerLayer(serviceName) >+>
      EdgeApp.openTelemetryLayer(serviceName)

  override def run: ZIO[Environment & ZIOAppArgs & zio.Scope, Any, Any] = {
    for
      opentelemetry <- ZIO.service[api.OpenTelemetry]
      edgeConfig <- ZIO.service[EdgeConfig]
      envConfig <- ZIO.service[ConfigProvider]
      tracing <- ZIO.service[Tracing]
      readinessService <- ReadinessService.make

      _ <- (Server.install[MetricsService](serviceRoutes(readinessService)) *> ZIO.never)
        .provide(
          Server.live,
          EdgeApp.prometheusMetricsService,
          ZLayer.succeed(MetricsConfig(1.second)),
          ZLayer.succeed(config.diagnostics),
        ).fork

      _ <- {
        for
          _ <- ZIO.logInfo("Starting application server")

          port <- Server.install {
            routes @@
              Observability.middleware @@
              Middleware.metrics()
          }
          _ <- ZIO.logInfo(s"Application server is started and ready to use on $port")
          _ <- readinessService.setReady
          _ <- ZIO.never
        yield ()
      }.provide(
        services,
        Server.live,
        ZLayer.succeed(config.server),
        ZLayer.succeed(config.observability),
        ZLayer.succeed(opentelemetry),
        ZLayer.succeed(tracing),
        ZLayer.succeed(edgeConfig),
        ZLayer.succeed(envConfig),
      )
    yield ()
  }
    .catchAll { (ex: Throwable) => ZIO.logErrorCause("Could not start application", Cause.fail(ex)) }
    .catchAllDefect(ex => ZIO.logErrorCause("Could not start application", Cause.die(ex)))

  private def serviceRoutes(
      readinessService: ReadinessService,
  ): Routes[MetricsService, Nothing] =
    Routes(
      Method.GET / "metrics" -> handler { (_: Request) =>
        ZIO.serviceWithZIO[MetricsService](_.get.map(Response.text(_)))
      },
      Method.GET / "liveness" -> handler { (_: Request) => Response.status(Status.Ok) },
      Method.GET / "readiness" -> handler { (_: Request) =>
        readinessService.isReady.map:
          case true => Response.status(Status.Ok)
          case false => Response.status(Status.ServiceUnavailable)
      },
    )

  given environmentTag: Tag[Environment] = Tag[Environment]

  given Tag[Services] = Tag[Services]

  def parseConfig[A: {DeriveConfig, Tag}] =
    ZLayer:
      ZIO.serviceWithZIO[ConfigProvider](_.load(deriveConfig[A]))

object EdgeApp:
  case class Config(
      server: Server.Config,
      diagnostics: Server.Config,
      observability: HttpObservabilityConfig,
  ):
    def withPort(port: Int): Config =
      copy(server = server.port(port))

    def withRequestStreaming(streaming: RequestStreaming): Config =
      copy(server = server.requestStreaming(streaming))

    def withDiagnosticsPort(port: Int): Config =
      copy(diagnostics = diagnostics.port(port))

    def withMasking(masking: (Path, HttpObservabilityConfig.Masking)*) =
      copy(
        observability = observability.copy(masking = observability.masking ++ masking),
      )

  object Config:
    val default = Config(
      server = Server.Config.default.port(8081),
      diagnostics = Server.Config.default.port(9346),
      observability = HttpObservabilityConfig.default,
    )

  private def config: ZLayer[Any, zio.Config.Error | Throwable, EdgeConfig & ConfigProvider] = {
    val layer = ZLayer.fromZIO:
      for
        configString <- System.env("ENV_CONFIG").flatMap:
          case Some(envConfig) => ZIO.succeed(envConfig)
          case None =>
            for
              path <- System.property("env.path")
                .someOrFail(RuntimeException("Neither 'ENV_CONFIG' env var nor 'env.path' property is set"))
              absolutePath <- ZIO.attempt(java.nio.file.Paths.get(path).toAbsolutePath.toString)
            yield s"""include required(file("$absolutePath"))"""

        config = ConfigFactory.parseString(configString)

        core <- ConfigProvider
          .fromTypesafeConfig(config.getConfig("core")).kebabCase
          .load(deriveConfig[EdgeConfig])

        app <- ConfigProvider
          .fromTypesafeConfigZIO(config.getConfig("app")).map(_.kebabCase)
      yield (core, app)

    layer.map(env => ZEnvironment(env.get._1) ++ ZEnvironment(env.get._2))
  }

  private given DeriveConfig[EnvName] = DeriveConfig:
    zio.Config.string.map:
      case "prod" => EnvName.Prod
      case value => EnvName.Test(value)

  private def jsonLoggerLayer(serviceName: String): ZLayer[LogFormats & EdgeConfig, Nothing, Unit] =
    zio.Runtime.removeDefaultLoggers >>> ZLayer {
      import LogFormat.*
      for
        formats <- ZIO.service[LogFormats]
        envConfig <- ZIO.service[EdgeConfig]
        config = ConsoleLoggerConfig.default.copy(
          format =
            List(
              label("timestamp", timestamp.fixed(32)),
              label("system", text(serviceName)),
              label("env", text(envConfig.runtime.env.value)),
              label("level", level),
              label("thread", fiberId),
              label("message", quoted(line)) +
                (space + label("stack_trace", cause)).filter(LogFilter.causeNonEmpty),
              formats.spanIdLabel,
              formats.traceIdLabel,
              logAnnotation(Observability.logCause),
              logAnnotation(Observability.receiveHttp),
              label("logger", LoggerNameExtractor.loggerNameAnnotationOrTrace.toLogFormat()),
            ).reduce(_ |-| _),
        )
      yield zio.logging.consoleJsonLogger(config) >+> Slf4jBridge.initialize
    }.flatten

  /**
   * OpenTelemetry layer for EdgeApp that works with EdgeConfig instead of CoreConfig.
   *
   * This is a minimal implementation that builds OpenTelemetry from EdgeConfig's telemetry settings.
   * EdgeConfig.Telemetry has the same structure as CoreConfig.Telemetry (just a collector URL).
   */
  private def openTelemetryLayer(serviceName: String): RLayer[ContextStorage & EdgeConfig, api.OpenTelemetry & Tracing] =
    ZLayer.fromZIO {
      ZIO.serviceWith[EdgeConfig]: config =>
        val resource = config.telemetry.fold(Resource.empty())(_ =>
          Resource.create(
            Attributes.of(
              ServiceAttributes.SERVICE_NAME,
              s"$serviceName-${config.runtime.env.value}",
            ),
          ),
        )

        OpenTelemetry.custom(
          for {
            spanExporter <- config.telemetry match
              case None =>
                ZIO.succeed(NoopSpanExporter)
              case Some(telemetry) =>
                ZIO.fromAutoCloseable:
                  ZIO.succeed:
                    OtlpGrpcSpanExporter.builder()
                      .setEndpoint(telemetry.collector)
                      .build()

            spanProcessor <- ZIO.fromAutoCloseable:
              ZIO.succeed:
                BatchSpanProcessor.builder(spanExporter).build()

            tracerProvider = SdkTracerProvider
              .builder()
              .setResource(resource)
              .addSpanProcessor(spanProcessor)
              .build()

            openTelemetry <- ZIO.fromAutoCloseable(
              ZIO.succeed(
                OpenTelemetrySdk
                  .builder()
                  .setTracerProvider(tracerProvider)
                  .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                  .build,
              ),
            )
          } yield openTelemetry,
        )
    }.flatten >+> OpenTelemetry.tracing(
      instrumentationScopeName = "versola.http",
      instrumentationVersion = None,
    ) ++ ZLayer.service[api.OpenTelemetry]

  private object NoopSpanExporter extends io.opentelemetry.sdk.trace.`export`.SpanExporter:
    override def `export`(spans: java.util.Collection[io.opentelemetry.sdk.trace.data.SpanData]) =
      io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()

    override def flush() =
      io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()

    override def shutdown() =
      io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()

  private val prometheusMetricsService: ZLayer[MetricsConfig, Throwable, MetricsService] =
    (publisherLayer >+> prometheusLayer) >+> ZLayer.fromZIO:
      ZIO.serviceWith[PrometheusPublisher]: publisher =>
        new MetricsService {
          override def get: UIO[String] = publisher.get
        }

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private def parseBase64UrlSecret(newType: ByteArrayNewType.FixedLength)(str: String) =
    newType.fromBase64Url(str)
      .left.map(message => zio.Config.Error.InvalidData(message = message))
      .filterOrElse(
        _.length == newType.length,
        zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"),
      )

