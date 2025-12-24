package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.fasterxml.uuid.Generators
import com.nimbusds.jose.jwk.JWKSet
import com.typesafe.config.ConfigFactory
import io.opentelemetry.api
import versola.admin.AdminController
import versola.auth.*
import versola.oauth.authorize.{AuthorizeEndpointController, AuthorizeEndpointService, AuthorizeRequestParser}
import versola.oauth.client.{OAuthClientRepository, OAuthClientService, OAuthScopeRepository}
import versola.oauth.client.model.{ClientId, OAuthClientRecord, Scope, ScopeToken}
import versola.oauth.conversation.otp.{EmailOtpProvider, OtpDecisionService, OtpGenerationService, OtpService}
import versola.oauth.conversation.{ConversationController, ConversationRenderService, ConversationRepository, ConversationRouter, ConversationService}
import versola.oauth.session.SessionRepository
import versola.oauth.token.AuthorizationCodeRepository
import versola.user.*
import versola.util.*
import versola.util.http.{HttpObservabilityConfig, MetricsService, Observability, OpenTelemetryBuilder, ReadinessService}
import zio.*
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

import java.security.PrivateKey
import scala.util.Try

abstract class OAuthApp extends ZIOApp:
  private val serviceName = "auth"

  override type Environment =
    ContextStorage & CoreConfig & ConfigProvider & LogFormats & api.OpenTelemetry & Tracing

  import versola.util.http.HttpObservabilityConfig.Masking

  val config = OAuthApp.Config.default
    .withPort(8080)
    .withDiagnosticsPort(9345)
    .withRequestStreaming(RequestStreaming.Disabled(1024 * 500))
    .withMasking(
      Path.root / "auth" / "otp" -> Masking(
        logResponseBody = false,
      ),
      Path.root / "auth" / "refresh" -> Masking(
        logRequestBody = false,
        logResponseBody = false,
      ),
    )

  type Repositories =
    UserRepository &
      // BanRepository &
      OAuthClientRepository &
      OAuthScopeRepository &
      ConversationRepository &
      SessionRepository &
      AuthorizationCodeRepository

  type ServiceProviders =
    EmailOtpProvider

  type Services =
    JWKSet &
      ConversationService &
      ConversationRouter &
      OAuthClientService &
      OAuthScopeRepository &
      SecureRandom &
      AuthorizeRequestParser &
      AuthorizeEndpointService &
      PostInitializationService &
      ConversationRenderService

  def routes: Routes[Services & CoreConfig & Tracing, Nothing] =
    List(
      AdminController.routes,
      AuthorizeEndpointController.routes,
      ConversationController.routes,
    ).reduce(_ ++ _)

  def dependencies: RLayer[zio.Scope & ConfigProvider, Repositories & ServiceProviders]

  val services: ZLayer[ConfigProvider & CoreConfig & Tracing, Throwable, Services] =
    val configs =
      val root = ZLayer.service[CoreConfig]
      root.project(_.jwt) ++
        root.project(_.jwt.jwkSet) ++
        root.project(_.security.clientSecrets) ++
        root.project(_.security.refreshTokens) ++
        root.project(_.security.authConversation)

    (zio.Scope.default >+> dependencies) >>>
      ZLayer.makeSome[zio.Scope & CoreConfig & Repositories & ServiceProviders & Tracing, Services](
        ConversationService.live,
        ConversationRenderService.live,
        ConversationRouter.live,
        AuthPropertyGenerator.live,
        ZLayer.fromFunction(OAuthClientService.Impl(_, _, _, _, _, _, _)),
        ZLayer.fromFunction(OtpService.Impl(_, _, _)),
        ZLayer.fromFunction(OtpGenerationService.Impl(_, _)),
        ZLayer.succeed(OtpDecisionService.Impl()),
        SecureRandom.live,
        SecurityService.live,
        // SmsClient.live,
        AuthorizeRequestParser.live,
        AuthorizeEndpointService.live,
        ZLayer.succeed(PostInitializationService.Impl()),
        ZLayer.succeed(ConversationRenderService.live),
        ZLayer.fromZIO(ReloadingCache.make[Set[Email]]()),
        ZLayer.fromZIO(ReloadingCache.make[Map[ScopeToken, Scope]](Schedule.spaced(1.minute))),
        ZLayer.fromZIO(ReloadingCache.make[Map[ClientId, OAuthClientRecord]](Schedule.spaced(1.minute))),
        Client.default,
        configs,
      ) >+> ZLayer(ZIO.serviceWithZIO[PostInitializationService](_.postInitialize().map(identity[Any])))

  override val bootstrap: ZLayer[Any, Any, Environment] =
    OAuthApp.config >+>
      OpenTelemetry.contextZIO >+>
      ZioLogging.logFormats >+>
      OAuthApp.jsonLoggerLayer(serviceName) >+>
      OpenTelemetryBuilder.live(
        serviceName = serviceName,
      )

  override def run: ZIO[Environment & ZIOAppArgs & zio.Scope, Any, Any] = {
    for
      opentelemetry <- ZIO.service[api.OpenTelemetry]
      coreConfig <- ZIO.service[CoreConfig]
      envConfig <- ZIO.service[ConfigProvider]
      tracing <- ZIO.service[Tracing]
      readinessService <- ReadinessService.make

      _ <- (Server.install[MetricsService](serviceRoutes(readinessService)) *> ZIO.never)
        .provide(
          Server.live,
          OAuthApp.prometheusMetricsService,
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

object OAuthApp:
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
    newType.fromBase64Url(str)
      .left.map(message => zio.Config.Error.InvalidData(message = message))
      .filterOrElse(
        _.length == newType.length,
        zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"),
      )
