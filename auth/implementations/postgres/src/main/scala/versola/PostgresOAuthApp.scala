package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.cleanup.PostgresCleanupManager
import versola.oauth.PostgresAuthorizationCodeRepository
import versola.oauth.authorize.{AuthorizeEndpointController, AuthorizeEndpointService, AuthorizeRequestParser}
import versola.oauth.challenge.password.{PasswordRepository, PasswordService, PostgresPasswordRepository}
import versola.oauth.client.{OAuthClientSyncClient, OAuthConfigurationService, OAuthScopeSyncClient}
import versola.oauth.conversation.otp.{EmailOtpProvider, OtpGenerationService, OtpService}
import versola.oauth.conversation.{
  ConversationController,
  ConversationRenderService,
  ConversationRepository,
  ConversationRouter,
  ConversationService,
  PostgresConversationRepository,
}
import versola.oauth.introspect.{IntrospectionController, IntrospectionService}
import versola.oauth.jwks.JwksController
import versola.oauth.revoke.{AccessTokenRevocationService, RevocationController, RevocationService}
import versola.oauth.session.{PostgresRefreshTokenRepository, PostgresSessionRepository, RefreshTokenRepository, SessionRepository}
import versola.oauth.token.{AuthorizationCodeRepository, OAuthTokenService, TokenEndpointController}
import versola.oauth.userinfo.{UserInfoController, UserInfoService}
import versola.user.{PostgresUserRepository, UserRepository}
import versola.util.*
import versola.util.JWT.PublicKeys
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.http.*
import zio.http.Server.RequestStreaming
import zio.json.ast
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.PrivateKey
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object PostgresOAuthApp extends VersolaApp("auth"):
  val environmentTag = Tag[Environment]

  override given Tag[Dependencies] = Tag[Dependencies]

  override def diagnosticsConfig: Server.Config =
    Server.Config.default.port(8081)

  override def serverConfig: Server.Config =
    Server.Config.default.port(8080)

  type Dependencies =
    CoreConfig &
      UserRepository &
      OAuthConfigurationService &
      ConversationRepository &
      AuthorizationCodeRepository &
      SessionRepository &
      RefreshTokenRepository &
      PasswordRepository &
      PasswordService &
      SecureRandom &
      SecurityService &
      AuthPropertyGenerator &
      OAuthTokenService &
      IntrospectionService &
      RevocationService &
      AccessTokenRevocationService &
      AuthorizeRequestParser &
      AuthorizeEndpointService &
      ConversationRouter &
      ConversationService &
      ConversationRenderService &
      OtpService &
      OtpGenerationService &
      EmailOtpProvider &
      UserInfoService

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    List(
      AuthorizeEndpointController.routes,
      TokenEndpointController.routes,
      IntrospectionController.routes,
      RevocationController.routes,
      ConversationController.routes,
      UserInfoController.routes,
      JwksController.routes,
    ).reduce(_ ++ _)

  val repositories = PostgresHikariDataSource.transactor(serviceName = Some("auth"), migrate = true) >+> (
    PostgresUserRepository.live >+>
      PostgresConversationRepository.live >+>
      PostgresAuthorizationCodeRepository.live >+>
      PostgresSessionRepository.live >+>
      PostgresRefreshTokenRepository.live >+>
      PostgresPasswordRepository.live >+>
      PostgresCleanupManager.live
  )

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing, Throwable, Dependencies] =
    repositories >+>
      parseConfig[CoreConfig] >+>
      Client.default >+>
      SecureRandom.live >+>
      SecurityService.live >+>
      OAuthConfigurationService.live(Schedule.spaced(1.minute)) >+>
      AuthPropertyGenerator.live >+>
      AccessTokenRevocationService.noop >+>
      OAuthTokenService.live >+>
      IntrospectionService.live >+>
      RevocationService.live >+>
      AuthorizeRequestParser.live >+>
      AuthorizeEndpointService.live >+>
      OtpGenerationService.live >+>
      ZLayer.succeed(versola.oauth.conversation.otp.OtpDecisionService.Impl()) >+>
      EmailOtpProvider.live >+>
      OtpService.live >+>
      PasswordService.live >+>
      UserInfoService.live >+>
      ConversationService.live >+>
      ConversationRouter.live >+>
      ConversationRenderService.live

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[SecretKey] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))
    .map(bytes => SecretKeySpec(bytes, "AES"))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[PrivateKey] = DeriveConfig[String]
    .mapOrFail: str =>
      PrivateKeyUtil.parse(str, "RSA")
        .left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))

  given DeriveConfig[ast.Json.Obj] = DeriveConfig[String]
    .mapOrFail: str =>
      ast.Json.decoder.decodeJson(str)
        .flatMap:
          case obj: ast.Json.Obj => Right(obj)
          case _ => Left("Expected JSON object")
        .left.map(msg => zio.Config.Error.InvalidData(message = msg))

  given DeriveConfig[PublicKeys] = DeriveConfig[ast.Json.Obj].map(PublicKeys.fromJson)

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
