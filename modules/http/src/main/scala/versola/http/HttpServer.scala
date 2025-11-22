package versola.http

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api
import versola.security.Secret
import versola.util.{ByteArrayNewType, CoreConfig, EnvName, PrivateKeyUtil}
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.*
import zio.http.*
import zio.http.Server.RequestStreaming
import zio.json.*
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, LoggerNameExtractor}
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}
import zio.*

import java.security.PrivateKey
import scala.util.Try

/**
 * Базовый трейт для приложений на основе HTTP сервера.
 *
 * Включено:
 * - OpenTelemetry трейсинг
 * - Структурированное JSON логирование
 * - ENV конфигурация
 * - Сервисные эндпоинтов liveness, readiness, metrics
 * - Graceful shutdown
 */
trait HttpServer(serviceName: String) extends ZIOApp:

  override type Environment =
    ContextStorage & CoreConfig & ConfigProvider & LogFormats & api.OpenTelemetry & Tracing

  type Services

  def services: ZLayer[CoreConfig & ConfigProvider & api.OpenTelemetry & Tracing, Throwable, Services]

  /**
   * Конфигурация HTTP сервера.
   * Определяет параметры сервера такие как порт, хост, таймауты и другие настройки.
   */
  def config: HttpServer.Config

  /**
   * Маршруты приложения.
   *
   * Определяет HTTP эндпоинты приложения. Маршруты автоматически получают доступ к:
   * - Конфигурации приложения через EnvConfig[AppConfig]
   * - OpenTelemetry для трейсинга
   * - Tracing для создания спанов
   *
   * @return маршруты HTTP приложения
   */
  def routes: Routes[Services & Tracing & CoreConfig & api.OpenTelemetry, Response]

  /**
   * Слой инициализации окружения приложения.
   *
   * Настраивает и предоставляет все необходимые сервисы:
   * - Загрузку конфигурации из файла
   * - OpenTelemetry контекст для трейсинга
   * - Форматы логирования
   * - JSON логгер с трейсинг информацией
   * - OpenTelemetry провайдер
   */
  override val bootstrap: ZLayer[Any, Any, Environment] =
    HttpServer.config >+>
      OpenTelemetry.contextZIO >+>
      ZioLogging.logFormats >+>
      HttpServer.jsonLoggerLayer(serviceName) >+>
      OpenTelemetryBuilder.live(
        serviceName = serviceName,
      )

  /**
   * Основная точка входа приложения.
   *
   * Запускает HTTP сервер с настроенными маршрутами и сервисными эндпоинтами.
   * Включает в себя:
   * - Сервисные маршруты на порту 9345 (liveness, readiness, metrics)
   * - Основные маршруты приложения на настроенном порту
   * - Observability middleware для трейсинга и логгирования HTTP запросов
   */
  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {
    for
      opentelemetry <- ZIO.service[api.OpenTelemetry]
      coreConfig <- ZIO.service[CoreConfig]
      envConfig <- ZIO.service[ConfigProvider]
      tracing <- ZIO.service[Tracing]
      readinessService <- ReadinessService.make

      _ <- (Server.install[MetricsService](serviceRoutes(readinessService)) *> ZIO.never)
        .provide(
          Server.live,
          HttpServer.prometheusMetricsService,
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
        ZLayer.succeed(coreConfig),
        ZLayer.succeed(envConfig),
      )
    yield ()
  }
    .catchAll { (ex: Throwable) => ZIO.logErrorCause("Could not start application", Cause.fail(ex)) }
    .catchAllDefect(ex => ZIO.logErrorCause("Could not start application", Cause.die(ex)))

  /**
   * Создает сервисные маршруты для мониторинга состояния приложения.
   *
   * Предоставляет стандартные эндпоинты:
   * - GET /liveness - проверка жизнеспособности (всегда возвращает 200 OK)
   * - GET /readiness - проверка готовности (зависит от состояния ReadinessService)
   * - GET /metrics - эндпоинт для метрик (заглушка)
   *
   * @param readinessService сервис для управления состоянием готовности
   * @return маршруты для сервисных эндпоинтов
   */
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

  given Tag[Services] = compiletime.deferred

object HttpServer:
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
      server = Server.Config.default.port(8080),
      diagnostics = Server.Config.default.port(9345),
      observability = HttpObservabilityConfig.default,
    )

  /**
   * Создает слой конфигурации приложения.
   *
   * Загружает конфигурацию из файла, указанного в системном свойстве 'env.path'.
   * Файл конфигурации должен быть в формате HOCON.
   */
  private def config: ZLayer[Any, zio.Config.Error | Throwable, CoreConfig & ConfigProvider] = {
    val layer = ZLayer.fromZIO:
      for
        path <- System.property("env.path")
          .someOrFail(RuntimeException("Property 'env.path' is not set. Provide it via `-Denv.path=...`"))

        absolutePath <- ZIO.attempt(java.nio.file.Paths.get(path).toAbsolutePath.toString)

        config = ConfigFactory.parseString:
          s"""
             |include required(file("$absolutePath"))
             |""".stripMargin

        core <- ConfigProvider
          .fromTypesafeConfig(config.getConfig("core")).kebabCase
          .load(deriveConfig[CoreConfig])

        app <- ConfigProvider
          .fromTypesafeConfigZIO(config.getConfig("app")).map(_.kebabCase)
      yield (core, app)

    layer.map(env => ZEnvironment(env.get._1) ++ ZEnvironment(env.get._2))
  }

  private given DeriveConfig[EnvName] = DeriveConfig:
    zio.Config.string.map:
      case "prod" => EnvName.Prod
      case value => EnvName.Test(value)

  /**
   * Создает слой для JSON логгирования в описанном формате
   */
  private def jsonLoggerLayer(serviceName: String): ZLayer[LogFormats & CoreConfig, Nothing, Unit] =
    zio.Runtime.removeDefaultLoggers >>> ZLayer {
      import LogFormat.*
      for
        formats <- ZIO.service[LogFormats]
        envConfig <- ZIO.service[CoreConfig]
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

  given DeriveConfig[PrivateKey] = DeriveConfig[String]
    .mapOrFail: string =>
      PrivateKeyUtil.parse(key = string, algorithm = "RSA")
        .left
        .map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))

  given DeriveConfig[zio.json.ast.Json.Obj] = DeriveConfig[String]
    .mapOrFail(_.fromJson[ast.Json.Obj].left.map(message => zio.Config.Error.InvalidData(message = message)))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private def parseBase64UrlSecret(newType: ByteArrayNewType.FixedLength)(str: String) =
    Try(newType.fromBase64Url(str)).toEither
      .left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))
      .filterOrElse(_.length == newType.length, zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"))
