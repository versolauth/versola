package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.fasterxml.uuid.Generators
import com.nimbusds.jose.jwk.JWKSet
import versola.admin.AdminController
import versola.auth.*
import versola.http.{HttpObservabilityConfig, HttpServer}
import versola.init.PostInitializationService
import versola.oauth.authorize.{AuthorizeEndpointController, AuthorizeRequestParser}
import versola.oauth.model.{ClientId, OAuthClient, Scope, ScopeToken}
import versola.oauth.{OAuthClientRepository, OAuthClientService, OAuthScopeRepository, PostgresOAuthClientRepository, PostgresOAuthScopeRepository}
import versola.security.{SecureRandom, SecurityService}
import versola.user.*
import versola.user.model.Email
import versola.util.*
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.http.*
import zio.http.Server.RequestStreaming
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthBackend extends AuthServer:

  case class AppConfig(
      databases: Map[String, PostgresConfig],
  )

  val repositories =
    parseConfig[AppConfig] >+>
      ZLayer.service[AppConfig].project(_.databases("postgres")) >>>
      (PostgresHikariDataSource.layer(migrate = true) >>> TransactorZIO.layer) >>> (
        ZLayer.fromFunction(PostgresEmailVerificationsRepository(_)) ++
          ZLayer.fromFunction(PostgresBanRepository(_)) ++
          ZLayer.fromFunction(PostgresUserDeviceRepository(_)) ++
          ZLayer.fromFunction(PostgresUserRepository(_, Generators.timeBasedEpochGenerator)) ++
          ZLayer.fromFunction(PostgresPasskeyRepository(_)) ++
          ZLayer.fromFunction(PostgresOAuthClientRepository(_)) ++
          ZLayer.fromFunction(PostgresOAuthScopeRepository(_)) ++
          ZLayer.fromFunction(PostgresConversationRepository(_))
      )

abstract class AuthServer extends HttpServer("auth"):

  import HttpObservabilityConfig.Masking

  val config = HttpServer.Config.default
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
      BanRepository &
      EmailVerificationsRepository &
      UserDeviceRepository &
      PasskeyRepository &
      OAuthClientRepository &
      OAuthScopeRepository &
      ConversationRepository

  type Services =
    AuthController.Env &
      UserController.Env &
      AdminController.Env &
      AuthorizeEndpointController.Env &
      PostInitializationService

  def routes: Routes[Services & Tracing & CoreConfig, Nothing] =
    List(
      UserController.routes,
      AuthController.routes,
      AdminController.routes,
      AuthorizeEndpointController.routes,
    ).reduce(_ ++ _)

  def repositories: RLayer[zio.Scope & ConfigProvider, Repositories]

  val services: ZLayer[ConfigProvider & CoreConfig & Tracing, Throwable, Services] =
    val configs =
      val root = ZLayer.service[CoreConfig]
      root.project(_.runtime.env) ++
        root.project(_.jwt) ++
        root.project(_.jwt.jwkSet) ++
        root.project(_.security.clientSecrets) ++
        root.project(_.security.refreshTokens)

    (zio.Scope.default >+> repositories) >>> ZLayer.makeSome[zio.Scope & CoreConfig & Repositories & Tracing, Services](
      ZLayer.fromFunction(AuthService.Impl(_, _, _, _, _, _, _, _)),
      ZLayer.fromFunction(OAuthClientService.Impl(_, _, _, _, _, _, _)),
      ZLayer.fromFunction(ConversationService.Impl(_)),
      UserService.live,
      TokenService.live,
      SecureRandom.live,
      SecurityService.live,
      // SmsClient.live,
      AuthorizeRequestParser.live,
      ZLayer.succeed(PostInitializationService.Impl()),
      ZLayer.fromZIO(ReloadingCache.make[Set[Email]]()),
      ZLayer.fromZIO(ReloadingCache.make[Map[ScopeToken, Scope]](Schedule.spaced(1.minute))),
      ZLayer.fromZIO(ReloadingCache.make[Map[ClientId, OAuthClient]](Schedule.spaced(1.minute))),
      Client.default,
      configs,
    ) >+> ZLayer(ZIO.serviceWithZIO[PostInitializationService](_.postInitialize().map(identity[Any])))

  override given Tag[Services] = Tag[Services]

  def parseConfig[A: {DeriveConfig, Tag}] =
    ZLayer:
      ZIO.serviceWithZIO[ConfigProvider](_.load(deriveConfig[A]))
