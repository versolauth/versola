package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.admin.AdminController
import versola.oauth.authorize.{AuthorizeEndpointController, AuthorizeEndpointService, AuthorizeRequestParser}
import versola.oauth.client.{OAuthClientService, OAuthScopeRepository}
import versola.oauth.conversation.otp.{EmailOtpProvider, OtpGenerationService, OtpService}
import versola.oauth.conversation.{ConversationController, ConversationRenderService, ConversationRepository, ConversationRouter, ConversationService, PostgresConversationRepository}
import versola.oauth.introspect.{IntrospectionController, IntrospectionService}
import versola.oauth.session.{PostgresSessionRepository, PostgresRefreshTokenRepository, SessionRepository, RefreshTokenRepository}
import versola.oauth.token.{AuthorizationCodeRepository, OAuthTokenService, TokenEndpointController}
import versola.oauth.userinfo.{UserInfoController, UserInfoService}
import versola.oauth.{PostgresAuthorizationCodeRepository, PostgresOAuthClientRepository, PostgresOAuthScopeRepository}
import versola.user.{PostgresUserRepository, UserRepository}
import versola.util.*
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.http.*
import zio.http.Server.RequestStreaming
import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.PrivateKey

object PostgresOAuthApp extends VersolaApp("auth"):
  val environmentTag = Tag[Environment]

  override def diagnosticsConfig: Server.Config =
    Server.Config.default.port(8081)

  override def serverConfig: Server.Config =
    Server.Config.default.port(8080)

  type Dependencies =
    CoreConfig &
    UserRepository &
    OAuthClientService &
    OAuthScopeRepository &
    ConversationRepository &
    AuthorizationCodeRepository &
    SessionRepository &
    RefreshTokenRepository &
    SecureRandom &
    SecurityService &
    AuthPropertyGenerator &
    OAuthTokenService &
    IntrospectionService &
    AuthorizeRequestParser &
    AuthorizeEndpointService &
    ConversationRouter &
    ConversationService &
    ConversationRenderService &
    OtpService &
    OtpGenerationService &
    EmailOtpProvider &
    UserInfoService &
    EnvName

  override def routes: Routes[Dependencies & Tracing, Nothing] =
    List(
      AdminController.routes,
      AuthorizeEndpointController.routes,
      TokenEndpointController.routes,
      IntrospectionController.routes,
      ConversationController.routes,
      UserInfoController.routes,
    ).reduce(_ ++ _)

  val dependencies: ZLayer[ConfigProvider & Tracing, Throwable, Dependencies] =
    ZLayer.scopedEnvironment:
      (parseConfig[CoreConfig] >+>
        parseConfig[EnvName] >+>
        (ZLayer.service[CoreConfig].project(_.postgres) >>>
          (PostgresHikariDataSource.layer(migrate = true) >>> TransactorZIO.layer) >>>
          (ZLayer.fromFunction(PostgresUserRepository(_)) >+>
            ZLayer.fromFunction(PostgresOAuthClientRepository(_)) >+>
            ZLayer.fromFunction(PostgresOAuthScopeRepository(_)) >+>
            ZLayer.fromFunction(PostgresConversationRepository(_)) >+>
            ZLayer.fromFunction(PostgresAuthorizationCodeRepository(_)) >+>
            ZLayer.fromFunction(PostgresSessionRepository(_)) >+>
            ZLayer.fromFunction(PostgresRefreshTokenRepository(_)))) >+>
        SecureRandom.live >+>
        SecurityService.live >+>
        AuthPropertyGenerator.live >+>
        ZLayer.scoped(ReloadingCache.make[Map[versola.oauth.client.model.ClientId, versola.oauth.client.model.OAuthClientRecord]]()) >+>
        ZLayer.scoped(ReloadingCache.make[Map[versola.oauth.client.model.ScopeToken, versola.oauth.client.model.Scope]]()) >+>
        ZLayer.fromFunction(OAuthClientService.Impl(_, _, _, _, _, _, _)) >+>
        OAuthTokenService.live >+>
        IntrospectionService.live >+>
        AuthorizeRequestParser.live >+>
        AuthorizeEndpointService.live >+>
        OtpGenerationService.live >+>
        ZLayer.succeed(versola.oauth.conversation.otp.OtpDecisionService.Impl()) >+>
        EmailOtpProvider.live >+>
        OtpService.live >+>
        ConversationService.live >+>
        ConversationRouter.live >+>
        ConversationRenderService.live >+>
        UserInfoService.live).build

  def parseConfig[A: {DeriveConfig, Tag}] =
    ZLayer:
      ZIO.serviceWithZIO[ConfigProvider](_.load(deriveConfig[A]))

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[PrivateKey] = DeriveConfig[String]
    .mapOrFail: str =>
      PrivateKeyUtil.parse(str, "RSA")
        .left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))

  given DeriveConfig[zio.json.ast.Json.Obj] = DeriveConfig[String]
    .mapOrFail: str =>
      zio.json.ast.Json.decoder.decodeJson(str)
        .flatMap:
          case obj: zio.json.ast.Json.Obj => Right(obj)
          case _ => Left("Expected JSON object")
        .left.map(msg => zio.Config.Error.InvalidData(message = msg))

  given DeriveConfig[EnvName] = DeriveConfig[String]
    .map:
      case "prod" => EnvName.Prod
      case value => EnvName.Test(value)

  private def parseBase64UrlSecret(newType: ByteArrayNewType.FixedLength)(str: String) =
    newType.fromBase64Url(str)
      .left.map(message => zio.Config.Error.InvalidData(message = message))
      .filterOrElse(
        _.length == newType.length,
        zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"),
      )

