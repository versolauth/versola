package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.antifraud.{
  Antifraud,
  AntifraudDetectors,
  AntifraudEventRepository,
  AntifraudEvents,
  AntifraudListRepository,
  AntifraudLists,
  AntifraudProposalRepository,
  PostgresAntifraudEventRepository,
  PostgresAntifraudListRepository,
  PostgresAntifraudProposalRepository,
  PostgresVelocityRepository,
  VelocityCounters,
  VelocityRepository,
  VelocityRules,
}
import versola.cleanup.PostgresCleanupManager
import versola.oauth.account.{AccountSettingsController, AccountSettingsService}
import versola.oauth.authorize.{
  AcrResolutionService,
  AuthorizationResponseService,
  AuthorizeEndpointController,
  AuthorizeEndpointService,
  AuthorizeRequestParser,
  PushedAuthorizationController,
  PushedAuthorizationRepository,
  PushedAuthorizationService,
  RequestObjectService,
}
import versola.oauth.challenge.passkey.{PasskeyRepository, PostgresPasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.{PasswordRepository, PasswordService, PostgresPasswordRepository}
import versola.oauth.client.CentralSyncTokenService
import versola.oauth.client.{OAuthClientSyncClient, OAuthConfigurationService, OAuthScopeSyncClient, ServiceController}
import versola.oauth.clientauth.{ClientAssertionRepository, ClientAssertionService, ClientAuthentication, PostgresClientAssertionRepository}
import versola.oauth.consent.{ConsentRepository, ConsentService, PostgresConsentRepository}
import versola.oauth.conversation.limit.{ChallengeThrottleRepository, PostgresChallengeThrottleRepository, SubmissionLimiter}
import versola.oauth.conversation.otp.{EmailOtpProvider, OtpGenerationService, OtpService, SmsOtpProvider}
import versola.oauth.conversation.{
  ConversationController,
  ConversationRenderService,
  ConversationRepository,
  ConversationRouter,
  ConversationService,
  PostgresConversationRepository,
}
import versola.oauth.dpop.{DpopNonceService, DpopProofRepository, DpopService, PostgresDpopProofRepository}
import versola.oauth.est.{EstCentralClient, EstController, EstService}
import versola.oauth.introspect.{IntrospectionController, IntrospectionService}
import versola.oauth.jwks.{JwksController, JwksService, JwksSyncClient}
import versola.oauth.logout.{BackChannelDispatcher, BackChannelOutbox, LogoutController, LogoutService}
import versola.oauth.metadata.{MetadataController, MetadataSyncClient}
import versola.oauth.mtls.EdgeCertificateTrust
import versola.oauth.revoke.{AccessTokenRevocationService, RevocationController, RevocationService}
import versola.oauth.session.{PostgresSessionRepository, PostgresUserAgentRepository, SessionRepository, SessionService, UserAgentRepository}
import versola.oauth.token.{AuthorizationCodeRepository, OAuthTokenService, TokenEndpointController}
import versola.oauth.userinfo.{UserInfoController, UserInfoService}
import versola.oauth.{PostgresAuthorizationCodeRepository, PostgresPushedAuthorizationRepository}
import versola.user.{PostgresUserRepository, UserController, UserRegistrationSyncClient, UserRepository, UserService}
import versola.util.*
import versola.util.SequentialLayers.*
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresConfigSnapshotRepository, PostgresHikariDataSource, PostgresWalMetrics}
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
      EdgeCertificateTrust &
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
      AntifraudEvents &
      AntifraudEventRepository &
      VelocityRepository &
      VelocityCounters &
      AntifraudListRepository &
      AntifraudLists &
      AntifraudProposalRepository &
      AntifraudDetectors &
      ConversationRenderService &
      OtpService &
      OtpGenerationService &
      SmsOtpProvider &
      EmailOtpProvider &
      UserInfoService &
      JwksService &
      SubmissionLimiter &
      AccountSettingsService &
      ChallengeThrottleRepository &
      LogoutService &
      SessionService &
      UserRegistrationSyncClient &
      EstCentralClient &
      EstService

  // central is the one peer auth cannot work without and waits for at start-up
  override protected def guardExemptPeers =
    ZIO.serviceWith[CoreConfig](config => Set(versola.util.http.OutboundGuard.peerOf(config.central.url)))

  override protected def internalPeers =
    ZIO.serviceWith[CoreConfig](config => Set(versola.util.http.OutboundGuard.peerOf(config.central.url)))

  override protected def accessLogConfigurable: Boolean = false

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
      EstController.routes,
    ).reduce(_ ++ _)

  override def additionalRoutes: Option[Routes[Dependencies & Tracing & EnvName, Throwable]] =
    Some(AccountSettingsController.routes)

  /** RFC 8705 §5: the endpoints a client certificate is relevant to, served a second time on
    * the listener that demands one.
    *
    * Exactly the five the metadata advertises as aliases, and EST's `simplereenroll` (RFC 7030), which
    * is authenticated by the certificate this listener validated and so can be served nowhere else.
    * Not the whole route set: the
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
        EstController.reenrollmentRoutes,
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
        Server.Config.default.binding(mutualTlsBindHost, mutualTlsPort).ssl(
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

  val repositories = PostgresHikariDataSource.transactor(serviceName = Some("auth"), migrate = runMigrations) >++> (
    PostgresWalMetrics.live() >++>
      PostgresUserRepository.live >++>
      PostgresConversationRepository.live >++>
      PostgresConsentRepository.live >++>
      PostgresAuthorizationCodeRepository.live >++>
      PostgresPushedAuthorizationRepository.live >++>
      PostgresSessionRepository.live >++>
      PostgresUserAgentRepository.live >++>
      PostgresPasswordRepository.live >++>
      PostgresPasskeyRepository.live >++>
      PostgresChallengeThrottleRepository.live >++>
      PostgresAntifraudEventRepository.live >++>
      PostgresVelocityRepository.live >++>
      PostgresAntifraudListRepository.live >++>
      PostgresAntifraudProposalRepository.live >++>
      PostgresCleanupManager.live
  )

  /** Argon2id hashing runs on the unbounded blocking pool; this applies the configured
    * concurrency cap (see `Argon2Config`).
    */
  private val securityService: URLayer[SecureRandom & CoreConfig, SecurityService] =
    ZLayer.service[CoreConfig].flatMap { env =>
      SecurityService.live(env.get[CoreConfig].argon2OrDefault)
    }

  /** The `antifraud` block of the config, every key of which is optional; without it the defaults apply. */
  private val antifraudSettings: ZLayer[ConfigProvider, Config.Error, AntifraudEvents.Settings] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[ConfigProvider](
        _.load(Config.Nested("antifraud", deriveConfig[AntifraudEvents.Settings]).withDefault(AntifraudEvents.Settings.default)),
      )

  /** `antifraud.lists`, optional like the rest of the block. */
  private val antifraudListSettings: ZLayer[ConfigProvider, Config.Error, AntifraudLists.Settings] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[ConfigProvider](
        _.load(
          Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings]).withDefault(AntifraudLists.Settings.default))
            .withDefault(AntifraudLists.Settings.default),
        ),
      )

  /** `antifraud.detectors`, optional like the rest of the block. */
  private val antifraudDetectorSettings: ZLayer[ConfigProvider, Config.Error, AntifraudDetectors.Settings] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[ConfigProvider](
        _.load(
          Config.Nested(
            "antifraud",
            Config.Nested("detectors", deriveConfig[AntifraudDetectors.Settings]).withDefault(AntifraudDetectors.Settings.default),
          )
            .withDefault(AntifraudDetectors.Settings.default),
        ),
      )

  /** Keyed off the auth-central transport secret, see [[ConfigSnapshot]]. */
  private val configSnapshot: ZLayer[TransactorZIO & CoreConfig, Nothing, ConfigSnapshot] =
    (PostgresConfigSnapshotRepository.live("auth_config_snapshots") ++ ZLayer.service[CoreConfig]) >>>
      ZLayer.fromZIO:
        for
          repository <- ZIO.service[ConfigSnapshot.Repository]
          config <- ZIO.service[CoreConfig]
          snapshot <- ConfigSnapshot.make(repository, config.central.secretKey.getEncoded)
        yield snapshot

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    repositories >++>
      parseConfig[CoreConfig] >++>
      configSnapshot >++>
      SecureRandom.live >++>
      securityService >++>
      // Sizes its own expiry ring from `dpop.iat-leeway`, so it has to follow the config.
      PostgresDpopProofRepository.live >++>
      PostgresClientAssertionRepository.live >++>
      DpopNonceService.live >++>
      // The issuer of edges' certificates, read once: `/userinfo` takes a call over one as an edge's.
      EdgeCertificateTrust.live >++>
      JsonSchemaValidator.live >++>
      OAuthConfigurationService.live >++>
      DpopService.live >++>
      // Reads the algorithms an assertion may be signed with off the same document.
      ClientAssertionService.live >++>
      // And the algorithms a JAR request object may be signed with, likewise.
      RequestObjectService.live >++>
      ClientAuthentication.live >++>
      CentralSyncTokenService.live >++>
      JwksSyncClient.live >++>
      MetadataSyncClient.live >++>
      JwksService.live >++>
      AuthPropertyGenerator.live >++>
      SessionService.live >++>
      BackChannelDispatcher.live >++>
      BackChannelOutbox.live >++>
      AccessTokenRevocationService.live >++>
      OAuthTokenService.live >++>
      IntrospectionService.live >++>
      RevocationService.live >++>
      AuthorizeRequestParser.live >++>
      PushedAuthorizationService.live >++>
      OtpGenerationService.live >++>
      ZLayer.succeed(versola.oauth.conversation.otp.OtpDecisionService.Impl()) >++>
      EmailOtpProvider.live >++>
      SmsOtpProvider.live >++>
      OtpService.live >++>
      PasswordService.live >++>
      AuthBootstrapService.live >++>
      WebAuthnService.live >++>
      UserInfoService.live >++>
      SubmissionLimiter.live >++>
      AccountSettingsService.live >++>
      AcrResolutionService.live >++>
      UserRegistrationSyncClient.live >++>
      EstCentralClient.live >++>
      EstService.live >++>
      UserService.live >++>
      ConsentService.live >++>
      antifraudSettings >++>
      antifraudListSettings >++>
      AntifraudLists.live >++>
      ZLayer.fromFunction((settings: AntifraudEvents.Settings) => settings.velocity) >++>
      VelocityCounters.live >++>
      // Each component that adds rules is listed here.
      ZLayer.fromFunction((settings: AntifraudEvents.Settings, lists: AntifraudLists) =>
        Antifraud.Impl(settings.decision, List(lists.ruleSet, VelocityRules.ruleSet(settings.velocity))): Antifraud,
      ) >++>
      AntifraudEvents.live >++>
      antifraudDetectorSettings >++>
      AntifraudDetectors.live >++>
      ConversationService.live >++>
      ConversationRouter.live >++>
      AuthorizationResponseService.live >++>
      AuthorizeEndpointService.live >++>
      ConversationRenderService.live >++>
      LogoutService.live

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes32))

  given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail(SecretConfig.secret)

  given DeriveConfig[SecretKey] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes32))
    .map(bytes => SecretKeySpec(bytes, "AES"))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[Method] = DeriveConfig[String].map(Method.fromString)

  given DeriveConfig[Email] = DeriveConfig[String]
    .mapOrFail(Email.from(_).left.map(message => zio.Config.Error.InvalidData(message = message)))

  given DeriveConfig[PrivateKey] = DeriveConfig[String]
    .mapOrFail(SecretConfig.signingPrivateKey)

  given DeriveConfig[EnvName] = DeriveConfig[String]
    .map:
      case "prod" => EnvName.Prod
      case value => EnvName.Test(value)
