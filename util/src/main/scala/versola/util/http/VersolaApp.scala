package versola.util.http

import com.augustnagro.magnum.magzio.TransactorZIO
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
import versola.cleanup.{CleanupConfig, CleanupManager}
import versola.util.{EnvName, PostInitializationService}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.http.{Method, Middleware, Request, Response, Routes, Server, Status, handler}
import zio.logging.LogFormat.{cause, fiberId, label, level, line, logAnnotation, quoted, space, text, timestamp}
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, LoggerNameExtractor}
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus.{PrometheusPublisher, prometheusLayer, publisherLayer}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}

trait VersolaApp(serviceName: String) extends ZIOApp:
  import VersolaApp.*

  type Dependencies

  given Tag[Dependencies] = scala.compiletime.deferred

  override type Environment =
    ContextStorage & ConfigProvider & LogFormats & api.OpenTelemetry & Tracing & EnvName

  override val bootstrap: ZLayer[Any, Any, Environment] =
    OpenTelemetry.contextZIO >+> configProvider >+> envName >+>
      ZioLogging.logFormats >+>
      jsonLoggerLayer(serviceName) >+>
      openTelemetryLayer(serviceName)

  def dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing, Throwable, Dependencies]

  def routes: Routes[Dependencies & Tracing, Nothing]

  def diagnosticsConfig: Server.Config

  def serverConfig: Server.Config

  def observabilityConfig: HttpObservabilityConfig = HttpObservabilityConfig.default

  override def run: ZIO[Environment & ZIOAppArgs & zio.Scope, Any, Any] = {
    for
      opentelemetry <- ZIO.service[api.OpenTelemetry]
      envConfig <- ZIO.service[ConfigProvider]
      tracing <- ZIO.service[Tracing]
      envName <- ZIO.service[EnvName]
      scope <- ZIO.scope
      readinessService <- ReadinessService.make

      _ <- (Server.install[MetricsService](serviceRoutes(readinessService)) *> ZIO.never)
        .provide(
          Server.live,
          prometheusMetricsService,
          ZLayer.succeed(MetricsConfig(1.second)),
          ZLayer.succeed(diagnosticsConfig),
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
        dependencies,
        Server.live,
        ZLayer.succeed(serverConfig),
        ZLayer.succeed(observabilityConfig),
        ZLayer.succeed(opentelemetry),
        ZLayer.succeed(tracing),
        ZLayer.succeed(envConfig),
        ZLayer.succeed(envName),
        ZLayer.succeed(scope)
      )
    yield ()
  }
    .catchAll { (ex: Throwable) => ZIO.logErrorCause("Could not start application", Cause.fail(ex)) }
    .catchAllDefect(ex => ZIO.logErrorCause("Could not start application", Cause.die(ex)))


  def parseConfig[A: {DeriveConfig, Tag}] =
    ZLayer:
      ZIO.serviceWithZIO[ConfigProvider](_.load(deriveConfig[A]))

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

object VersolaApp:

  private def envName: ZLayer[ConfigProvider, Config.Error, EnvName] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[ConfigProvider](_.load(Config.string("env"))).map:
        case "prod" => EnvName.Prod
        case value => EnvName.Test(value)

  private def configProvider: TaskLayer[ConfigProvider] =
    ZLayer.fromZIO:
      for
        configString <- System.env("ENV_CONFIG").flatMap:
          case Some(envConfig) => ZIO.succeed(envConfig)
          case None =>
            for
              path <- System.property("env.path")
                .someOrFail(RuntimeException("Neither 'ENV_CONFIG' env var nor 'env.path' property is set"))
              absolutePath <- ZIO.attempt(java.nio.file.Paths.get(path).toAbsolutePath.toString)
            yield s"""include required(file("$absolutePath"))"""

        cp <- ConfigProvider.fromTypesafeConfigZIO(ConfigFactory.parseString(configString)).map(_.kebabCase)
      yield cp

  private def jsonLoggerLayer(serviceName: String): ZLayer[LogFormats & ConfigProvider, Config.Error, Unit] =
    zio.Runtime.removeDefaultLoggers >>> ZLayer {
      import LogFormat.*
      for
        formats <- ZIO.service[LogFormats]
        env <- ZIO.serviceWithZIO[ConfigProvider](_.load(Config.string("env")))
        config = ConsoleLoggerConfig.default.copy(
          format =
            List(
              label("timestamp", timestamp.fixed(32)),
              label("system", text(serviceName)),
              label("env", text(env)),
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

  private val prometheusMetricsService: ZLayer[MetricsConfig, Throwable, MetricsService] =
    (publisherLayer >+> prometheusLayer) >+> ZLayer.fromZIO:
      ZIO.serviceWith[PrometheusPublisher]: publisher =>
        new MetricsService {
          override def get: UIO[String] = publisher.get
        }

  private def openTelemetryLayer(serviceName: String): RLayer[ContextStorage & EnvName & ConfigProvider, api.OpenTelemetry & Tracing] =
    ZLayer.fromZIO {
      for
        envName <- ZIO.service[EnvName]
        exporter <- ZIO.serviceWithZIO[ConfigProvider](_.load(Config.Optional(Config.string("otel-exporter"))))
        resource = exporter.fold(Resource.empty())(_ =>
          Resource.create(
            Attributes.of(
              ServiceAttributes.SERVICE_NAME,
              s"$serviceName-$envName",
            ),
          ),
        )

        otel = OpenTelemetry.custom(
          for {
            spanExporter <- exporter match
              case None =>
                ZIO.succeed(NoopSpanExporter)
              case Some(edp) =>
                ZIO.fromAutoCloseable:
                  ZIO.succeed:
                    OtlpGrpcSpanExporter.builder()
                      .setEndpoint(edp)
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
      yield otel
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
