package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.fasterxml.uuid.Generators
import com.nimbusds.jose.jwk.JWKSet
import versola.AuthServer.AppConfig
import versola.admin.AdminController
import versola.auth.*
import versola.user.model.Email
import versola.http.{HttpObservabilityConfig, HttpServer}
import versola.init.PostInitializationService
import versola.email.{EmailService, EmailServiceProvider}
import versola.email.config.{EmailProviderConfig, MailgunConfig, SmtpConfig}
import versola.email.model.EmailAddress
import versola.oauth.authorize.{AuthorizeEndpointController, AuthorizeRequestParser}
import zio.config.magnolia.DeriveConfig
import zio.Config
import versola.oauth.model.{ClientId, OAuthClient, Scope, ScopeToken}
import versola.oauth.{OAuthClientRepository, OAuthScopeRepository, OauthClientService, PostgresOAuthClientRepository, PostgresOAuthScopeRepository}
import versola.user.*
import versola.util.*
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*
import zio.http.*
import zio.http.Server.RequestStreaming
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.PrivateKey

object AuthBackend extends AuthServer[PostgresConfig]:

  val repositories =
    ZLayer.service[EnvConfig[AppConfig[PostgresConfig]]].project(_.app.databases("postgres")) >>>
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

abstract class AuthServer[DatabaseConfig: {Tag, DeriveConfig}]
  extends HttpServer[AuthServer.AppConfig[DatabaseConfig]]("auth"):

  import AuthServer.*
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
      PostInitializationService &
      EmailService

  type Configs =
    EnvName &
      JwtConfig &
      JWKSet &
      ProvidersConfig &
      EmailProviderConfig

  def routes: Routes[Services & Tracing & EnvConfig[AppConfig[DatabaseConfig]], Nothing] =
    List(
      UserController.routes,
      AuthController.routes,
      AdminController.routes,
      AuthorizeEndpointController.routes,
    ).reduce(_ ++ _)

  val configs: URLayer[EnvConfig[AppConfig[DatabaseConfig]], Configs] =
    val root = ZLayer.service[EnvConfig[AppConfig[DatabaseConfig]]]
    root.project(_.runtime.env) ++
      root.project(_.app.auth.jwt) ++
      root.project(_.app.auth.jwt.jwkSet) ++
      root.project(_.app.auth.providers) ++
      root.project(_.app.emailProvider)

  val repositories: RLayer[EnvConfig[AppConfig[DatabaseConfig]] & zio.Scope, Repositories]

  val services: ZLayer[EnvConfig[AppConfig[DatabaseConfig]] & Tracing, Throwable, Services] =
    (configs >+> (zio.Scope.default >+> repositories)) >>> ZLayer.makeSome[zio.Scope & Repositories & Configs & Tracing, Services](
      ZLayer.fromFunction(AuthService.Impl(_, _, _, _, _, _, _, _, _)),
      ZLayer.fromFunction(OauthClientService.Impl(_, _, _, _, _)),
      ZLayer.fromFunction(ConversationService.Impl(_)),
      UserService.live,
      TokenService.live,
      SecureRandom.live,
      // SmsClient.live,
      AuthorizeRequestParser.live,
      ZLayer.succeed(PostInitializationService.Impl()),
      ZLayer.fromZIO(ReloadingCache.make[Set[Email]]()),
      ZLayer.fromZIO(ReloadingCache.make[Map[ScopeToken, Scope]](Schedule.spaced(1.minute))),
      ZLayer.fromZIO(ReloadingCache.make[Map[ClientId, OAuthClient]](Schedule.spaced(1.minute))),
      Client.default,
      EmailServiceProvider.layer,
    ) >+> ZLayer(ZIO.serviceWithZIO[PostInitializationService](_.postInitialize().map(identity[Any])))

  override given Tag[Services] = Tag[Services]

object AuthServer {

  /** Структура этого класса должна соответствовать env.conf файлу */
  case class AppConfig[DatabaseConfig](
      databases: Map[String, DatabaseConfig],
      auth: AuthConfig,
      emailProvider: EmailProviderConfig,
  ) derives DeriveConfig

  case class AuthConfig(
      jwt: JwtConfig,
      providers: ProvidersConfig,
  )

  case class ProvidersConfig(
      google: OAuthProviderConfig,
      github: OAuthProviderConfig,
  ) derives DeriveConfig

  case class OAuthProviderConfig(
      clientId: String,
      clientSecret: String,
  ) derives DeriveConfig

  case class JwtConfig(
      privateKey: PrivateKey,
      publicKey: ast.Json.Obj,
  ):
    val jwkSet = JWKSet.parse(publicKey.toJson)

  given DeriveConfig[PrivateKey] = DeriveConfig[String]
    .mapOrFail: string =>
      PrivateKeyUtil.parse(key = string, algorithm = "RSA")
        .left
        .map(ex => Config.Error.InvalidData(message = ex.getMessage))

  given DeriveConfig[ast.Json.Obj] = DeriveConfig[String]
    .mapOrFail(_.fromJson[ast.Json.Obj].left.map(message => Config.Error.InvalidData(message = message)))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => Config.Error.InvalidData(message = ex.getMessage)))

  // Email configuration DeriveConfig instances
  given DeriveConfig[EmailAddress] = DeriveConfig[String]
    .mapOrFail(EmailAddress.from(_).left.map(message => Config.Error.InvalidData(message = message)))

  given DeriveConfig[SmtpConfig] = DeriveConfig.derived[SmtpConfig]
    .mapOrFail(validateSmtpConfig)

  given DeriveConfig[MailgunConfig] = DeriveConfig.derived[MailgunConfig]
    .mapOrFail(validateMailgunConfig)

  given DeriveConfig[EmailProviderConfig] = DeriveConfig.derived[EmailProviderConfig]
    .mapOrFail(validateEmailProviderConfig)

  private def validateSmtpConfig(config: SmtpConfig): Either[Config.Error, SmtpConfig] =
    val errors = List(
      Option.when(config.host.isEmpty)("SMTP host cannot be empty"),
      Option.when(config.port <= 0 || config.port > 65535)("SMTP port must be between 1 and 65535"),
      Option.when(config.username.isEmpty)("SMTP username cannot be empty"),
      Option.when(config.password.isEmpty)("SMTP password cannot be empty"),
      config.connectionTimeout.filter(_ <= 0).map(_ => "SMTP connection timeout must be positive"),
      config.timeout.filter(_ <= 0).map(_ => "SMTP timeout must be positive"),
    ).flatten

    if errors.nonEmpty then
      Left(Config.Error.InvalidData(message = s"SMTP configuration errors: ${errors.mkString(", ")}"))
    else
      Right(config)

  private def validateMailgunConfig(config: MailgunConfig): Either[Config.Error, MailgunConfig] =
    val errors = List(
      Option.when(config.apiKey.isEmpty)("Mailgun API key cannot be empty"),
      Option.when(config.domain.isEmpty)("Mailgun domain cannot be empty"),
      config.baseUrl.filter(_.isEmpty).map(_ => "Mailgun base URL cannot be empty"),
      config.baseUrl.filter(!_.startsWith("http")).map(_ => "Mailgun base URL must start with http or https"),
    ).flatten

    if errors.nonEmpty then
      Left(Config.Error.InvalidData(message = s"Mailgun configuration errors: ${errors.mkString(", ")}"))
    else
      Right(config)

  private def validateEmailProviderConfig(config: EmailProviderConfig): Either[Config.Error, EmailProviderConfig] =
    (config.smtp, config.mailgun) match
      case (Some(_), Some(_)) =>
        Left(Config.Error.InvalidData(message = "Only one email provider can be configured. Found both SMTP and Mailgun."))
      case (Some(_), None) =>
        Right(config)
      case (None, Some(_)) =>
        Right(config)
      case (None, None) =>
        Left(Config.Error.InvalidData(message = "At least one email provider must be configured (smtp or mailgun)."))
}
