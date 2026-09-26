package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.cleanup.PostgresCleanupManager
import versola.oauth.{PostgresAuthorizationCodeRepository, PostgresPushedAuthorizationRepository}
import versola.oauth.account.AccountSettingsController
import versola.oauth.authorize.{AcrResolutionService, AuthorizationResponseService, AuthorizeEndpointController, AuthorizeEndpointService, AuthorizeRequestParser, PushedAuthorizationController, PushedAuthorizationRepository, PushedAuthorizationService, RequestObjectService}
import versola.oauth.challenge.passkey.{PasskeyRepository, PostgresPasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.{PasswordRepository, PasswordService, PostgresPasswordRepository}
import versola.oauth.client.{ServiceController, OAuthClientSyncClient, OAuthConfigurationService, OAuthScopeSyncClient}
import versola.oauth.consent.{ConsentRepository, ConsentService, PostgresConsentRepository}
import versola.oauth.dpop.{DpopNonceService, DpopProofRepository, DpopService, EdgeAssertionService, PostgresDpopProofRepository}
import versola.oauth.conversation.otp.{EmailOtpProvider, SmsOtpProvider, OtpGenerationService, OtpService}
import versola.oauth.conversation.limit.{ChallengeThrottleRepository, PostgresChallengeThrottleRepository, SubmissionLimiter}
import versola.oauth.conversation.{ConversationController, ConversationRenderService, ConversationRepository, ConversationRouter, ConversationService, PostgresConversationRepository}
import versola.oauth.introspect.{IntrospectionController, IntrospectionService}
import versola.oauth.client.CentralSyncTokenService
import versola.oauth.jwks.{JwksController, JwksService, JwksSyncClient}
import versola.oauth.logout.{BackChannelDispatcher, BackChannelOutbox, LogoutController, LogoutService}
import versola.oauth.clientauth.{
  ClientAssertionRepository,
  ClientAssertionService,
  ClientAuthentication,
  PostgresClientAssertionRepository,
}
import versola.oauth.revoke.{AccessTokenRevocationService, RevocationController, RevocationService}
import versola.oauth.session.{PostgresSessionRepository, PostgresUserAgentRepository, SessionRepository, SessionService, UserAgentRepository}
import versola.oauth.token.{AuthorizationCodeRepository, OAuthTokenService, TokenEndpointController}
import versola.oauth.userinfo.{UserInfoController, UserInfoService}
import versola.oauth.metadata.{MetadataController, MetadataSyncClient}
import versola.user.{PostgresUserRepository, UserController, UserRegistrationSyncClient, UserRepository, UserService}
import versola.util.*
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.http.*
import zio.http.Server.RequestStreaming

import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.PrivateKey
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object PostgresOAuthApp extends VersolaApp("auth"):
  val environmentTag = Tag[Environment]

  override given Tag[Dependencies] = Tag[Dependencies]

  type Dependencies =
    CoreConfig &
      DpopProofRepository &
      DpopNonceService &
      ClientAssertionRepository &
      ClientAssertionService &
      DpopService &
      EdgeAssertionService &
      UserRepository &
      UserService &
      OAuthConfigurationService &
      ClientAuthentication &
      ConversationRepository &
      ConsentRepository &
      ConsentService &
      AuthorizationCodeRepository &
      PushedAuthorizationRepository &
      SessionRepository &
      UserAgentRepository &
      PasswordRepository &
      PasswordService &
      PasskeyRepository &
      WebAuthnService &
      SecureRandom &
      SecurityService &
      JsonSchemaValidator &
      AuthPropertyGenerator &
      OAuthTokenService &
      IntrospectionService &
      RevocationService &
      AccessTokenRevocationService &
      BackChannelDispatcher &
      BackChannelOutbox &
      AuthorizeRequestParser &
      PushedAuthorizationService &
      AuthorizeEndpointService &
      AuthorizationResponseService &
      ConversationRouter &
      ConversationService &
      ConversationRenderService &
      OtpService &
      OtpGenerationService &
      SmsOtpProvider &
      EmailOtpProvider &
      UserInfoService &
      JwksService &
      SubmissionLimiter &
      ChallengeThrottleRepository &
      LogoutService &
      SessionService &
      UserRegistrationSyncClient

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    List(
      AuthorizeEndpointController.routes,
      PushedAuthorizationController.routes,
      TokenEndpointController.routes,
      IntrospectionController.routes,
      RevocationController.routes,
      ConversationController.routes,
      UserInfoController.routes,
      JwksController.routes,
      MetadataController.routes,
      UserController.routes,
      ServiceController.routes,
      LogoutController.routes,
    ).reduce(_ ++ _)

  override def additionalRoutes: Option[Routes[Dependencies & Tracing & EnvName, Throwable]] =
    Some(AccountSettingsController.routes)

  /** RFC 8705 §5: the endpoints a client certificate is relevant to, served a second time on
    * the listener that demands one.
    *
    * Exactly the five the metadata advertises as aliases, and not the whole route set: the
    * rest either authenticate nobody (`/jwks` and the discovery documents) or are the
    * browser's (`/authorize`, the conversation pages), and a browser arriving here is
    * answered with a certificate prompt before it ever sends a request.
    */
  override def mutualTlsRoutes: Option[Routes[Dependencies & Tracing & EnvName, Throwable]] =
    Some(
      List(
        TokenEndpointController.routes,
        IntrospectionController.routes,
        RevocationController.routes,
        PushedAuthorizationController.routes,
        UserInfoController.routes,
      ).reduce(_ ++ _),
    )

  /** `ClientAuth.Required`, not `Optional`, and that is not a preference.
    *
    * `Optional` combined with `includeClientCert` is unusable in zio-http 3.6.0: the request
    * decoder reads `SSLSession.getPeerCertificates` unguarded, which throws
    * `SSLPeerUnverifiedException` when the peer presented nothing, and the connection is
    * dropped before any handler runs. Verified against 3.6.0 -- with `includeClientCert` off
    * the same certificate-less request is answered normally, so it is the read and not the
    * handshake that fails.
    *
    * `Required` is what this listener wants regardless: a request here with no certificate
    * has no endpoint to reach that the main listener does not serve better, and refusing it
    * in the handshake is a clearer answer than a 401 several layers later.
    */
  override def mutualTlsServerConfig: ZIO[Dependencies, Throwable, Option[Server.Config]] =
    ZIO.serviceWith[CoreConfig](
      _.mutualTls.map(mtls =>
        Server.Config.default.binding(bindHost, mutualTlsPort).ssl(
          SSLConfig.fromFile(
            behaviour = SSLConfig.HttpBehaviour.Fail,
            certPath = mtls.certificate,
            keyPath = mtls.privateKey,
            clientAuth = Some(ClientAuth.Required),
            trustCertCollectionPath = Some(mtls.trustedCertificates),
            // Without this the handshake still demands a certificate and validates it, and
            // the handler simply cannot see which one -- which would authenticate every
            // client as none of them.
            includeClientCert = true,
          ),
        ),
      ),
    )

  val repositories = PostgresHikariDataSource.transactor(serviceName = Some("auth"), migrate = runMigrations) >+> (
    PostgresUserRepository.live >+>
      PostgresConversationRepository.live >+>
      PostgresConsentRepository.live >+>
      PostgresAuthorizationCodeRepository.live >+>
      PostgresPushedAuthorizationRepository.live >+>
      PostgresSessionRepository.live >+>
      PostgresUserAgentRepository.live >+>
      PostgresPasswordRepository.live >+>
      PostgresPasskeyRepository.live >+>
      PostgresChallengeThrottleRepository.live >+>
      PostgresCleanupManager.live
  )

  /** Argon2id hashing runs on the unbounded blocking pool; this applies the configured
    * concurrency cap (see `Argon2Config`).
    */
  private val securityService: URLayer[SecureRandom & CoreConfig, SecurityService] =
    ZLayer.service[CoreConfig].flatMap { env =>
      SecurityService.live(env.get[CoreConfig].argon2OrDefault)
    }

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    repositories >+>
      parseConfig[CoreConfig] >+>
      SecureRandom.live >+>
      securityService >+>
      // Sizes its own expiry ring from `dpop.iat-leeway`, so it has to follow the config.
      PostgresDpopProofRepository.live >+>
      PostgresClientAssertionRepository.live >+>
      DpopNonceService.live >+>
      EdgeAssertionService.live >+>
      JsonSchemaValidator.live >+>
      OAuthConfigurationService.live >+>
      DpopService.live >+>
      // Reads the algorithms an assertion may be signed with off the same document.
      ClientAssertionService.live >+>
      // And the algorithms a JAR request object may be signed with, likewise.
      RequestObjectService.live >+>
      ClientAuthentication.live >+>
      CentralSyncTokenService.live >+>
      JwksSyncClient.live >+>
      MetadataSyncClient.live >+>
      JwksService.live >+>
      AuthPropertyGenerator.live >+>
      SessionService.live >+>
      BackChannelDispatcher.live >+>
      BackChannelOutbox.live >+>
      AccessTokenRevocationService.live >+>
      OAuthTokenService.live >+>
      IntrospectionService.live >+>
      RevocationService.live >+>
      AuthorizeRequestParser.live >+>
      PushedAuthorizationService.live >+>
      OtpGenerationService.live >+>
      ZLayer.succeed(versola.oauth.conversation.otp.OtpDecisionService.Impl()) >+>
      EmailOtpProvider.live >+>
      SmsOtpProvider.live >+>
      OtpService.live >+>
      PasswordService.live >+>
      AuthBootstrapService.live >+>
      WebAuthnService.live >+>
      UserInfoService.live >+>
      SubmissionLimiter.live >+>
      AcrResolutionService.live >+>
      UserRegistrationSyncClient.live >+>
      UserService.live >+>
      ConsentService.live >+>
      ConversationService.live >+>
      ConversationRouter.live >+>
      AuthorizationResponseService.live >+>
      AuthorizeEndpointService.live >+>
      ConversationRenderService.live >+>
      LogoutService.live

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail: str =>
      Secret.fromBase64Url(str)
        .left.map(message => zio.Config.Error.InvalidData(message = message))

  given DeriveConfig[SecretKey] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))
    .map(bytes => SecretKeySpec(bytes, "AES"))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[Method] = DeriveConfig[String].map(Method.fromString)

  given DeriveConfig[Email] = DeriveConfig[String]
    .mapOrFail(Email.from(_).left.map(message => zio.Config.Error.InvalidData(message = message)))

  given DeriveConfig[PrivateKey] = DeriveConfig[String]
    .mapOrFail: str =>
      PrivateKeyUtil.parse(str, "RSA")
        .left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))

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
