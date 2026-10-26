package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.typesafe.config.ConfigFactory
import versola.cleanup.PostgresCleanupManager
import versola.edge.dpop.{DpopPolicyService, DpopProofRepository, DpopReplayGuard, DpopVerifier}
import versola.edge.login.LoginRepository
import versola.edge.nativeapp.{NativeAuthClient, NativeController, NativeService}
import versola.edge.revocation.{RevocationNotifications, RevocationRepository, TokenRevocationService}
import versola.edge.session.EdgeSessionRepository
import versola.edge.{
  AccessLogPolicyService,
  AddressLimit,
  ClientCertificateFiles,
  ConfigurationSource,
  EdgeCertificateHolder,
  EdgeConfig,
  EdgeController,
  EdgeIdentity,
  EdgeRateLimiter,
  EdgeService,
  InternalSurface,
  JwksService,
  OAuthClientService,
  OverloadPolicyService,
  PermissionService,
  PostgresDpopProofRepository,
  PostgresEdgeSessionRepository,
  PostgresLoginRepository,
  PostgresRevocationNotifications,
  PostgresRevocationRepository,
  RateLimitPolicyService,
  RateLimitPolicySource,
  ResourceService,
  SSOClient,
  SSOClientWarmUp,
  ServiceController,
}
import versola.util.*
import versola.util.SequentialLayers.*
import versola.util.cel.CelEvaluator
import versola.util.http.{RateLimiter, VersolaApp}
import versola.util.postgres.{PostgresConfig, PostgresConfigSnapshotRepository, PostgresHikariDataSource, PostgresWalMetrics}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.*
import zio.http.*
import zio.http.Client
import zio.http.Server.RequestStreaming
import zio.telemetry.opentelemetry.tracing.Tracing

object PostgresEdgeApp extends VersolaApp("edge"):
  val environmentTag = Tag[Environment]

  override given Tag[Dependencies] = Tag[Dependencies]

  type Dependencies =
    EdgeConfig &
      SecureRandom &
      SecurityService &
      ConfigurationSource &
      EdgeCertificateHolder &
      EdgeIdentity &
      DpopPolicyService &
      OverloadPolicyService &
      AccessLogPolicyService &
      RateLimitPolicyService &
      RateLimiter &
      OAuthClientService &
      ResourceService &
      PermissionService &
      CelEvaluator &
      LoginRepository &
      EdgeSessionRepository &
      RevocationRepository &
      RevocationNotifications &
      TokenRevocationService &
      JwksService &
      ClientCertificateFiles &
      SSOClient &
      DpopProofRepository &
      DpopReplayGuard &
      DpopVerifier &
      EdgeService &
      NativeAuthClient &
      NativeService

  override protected def admissionTiers = Some(versola.edge.EdgeAdmission.tierOf)

  // what an operator sets for this edge in central, applied to the admission and the outbound guard as it changes
  override protected def overloadPolicy = Some(ZIO.serviceWithZIO[OverloadPolicyService](_.current))

  // central is the one peer the edge cannot work without and waits for at start-up
  override protected def guardExemptPeers =
    ZIO.serviceWith[EdgeConfig](config => Set(versola.util.http.OutboundGuard.peerOf(config.central.url)))

  // Per-request success lines are most of the edge's log and the metrics have them; failures and slow requests stay.
  // The only place these defaults are declared: there is no HTTP_ACCESS_LOG* environment. An operator's policy in central
  // is applied over them as it changes. auth and central always log every request.
  override protected def accessLogDefaults =
    versola.util.http.AccessLogSettings.default.copy(mode = versola.util.http.AccessLogMode.Off)
  override protected def accessLogPolicy = Some(ZIO.serviceWithZIO[AccessLogPolicyService](_.current))

  override protected def internalPeers =
    ZIO.serviceWith[EdgeConfig]: config =>
      import versola.util.http.OutboundGuard.peerOf
      Set(config.central.url, config.internalUrl, config.versolaUrl).map(peerOf)

  /** The surface every mode serves. The internal one is added by [[mountedRoutes]], which is
    * where the mode is known. */
  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    AddressLimit[Dependencies & Tracing & EnvName, Throwable](
      List(
        EdgeController.routes,
        NativeController.routes,
      ).reduce(_ ++ _),
      AddressLimit.Listener.Plain,
    )

  /** The proxy alone, on the listener that reads the caller's certificate (`EdgeConfig.mutualTls`). */
  override def mutualTlsRoutes: Option[Routes[Dependencies & Tracing & EnvName, Throwable]] =
    Some(AddressLimit[Dependencies & Tracing & EnvName, Throwable](EdgeController.resourceRoutes, AddressLimit.Listener.MutualTls))

  /** `ClientAuth.Required`, for the reason auth's listener gives (zio-http 3.6.0 drops a connection with an
    * optional certificate the peer did not present). A caller with no certificate has nothing to prove here
    * that the plain listener would not serve, and is refused in the handshake. */
  override def mutualTlsServerConfig: ZIO[Dependencies, Throwable, Option[Server.Config]] =
    ZIO.serviceWith[EdgeConfig](
      _.mutualTls.map(mtls =>
        Server.Config.default.binding(mutualTlsBindHost, mutualTlsPort).ssl(
          SSLConfig.fromFile(
            behaviour = SSLConfig.HttpBehaviour.Fail,
            certPath = mtls.certificate,
            keyPath = mtls.privateKey,
            clientAuth = Some(ClientAuth.Required),
            trustCertCollectionPath = Some(mtls.trustedCertificates),
            // Without this the handshake still validates the certificate and the handler cannot see which one.
            includeClientCert = true,
          ),
        ),
      ),
    )

  override def mountedRoutes: ZIO[Dependencies, Throwable, Routes[Dependencies & Tracing & EnvName, Throwable]] =
    ZIO.serviceWith[EdgeConfig](config => routes ++ InternalSurface.routes(config.mode))

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    // EdgeConfig.validated rejects a `versola-internal-trusted-certificates` that names a CA
    // rather than the leaf it is documented to require -- see that field's own comment.
    (parseConfig[EdgeConfig] >>> EdgeConfig.validated) >++>
      // `>++>` rather than `>>>`: PostgresRevocationNotifications needs the PostgresConfig the
      // transactor loaded, to open a connection of its own to park on LISTEN.
      (PostgresHikariDataSource.transactor(serviceName = Some("edge"), migrate = runMigrations) >++>
        (ZLayer.fromFunction(PostgresLoginRepository(_)) ++
          ZLayer.fromFunction(PostgresEdgeSessionRepository(_)) ++
          PostgresRevocationRepository.live ++
          PostgresDpopProofRepository.live ++
          PostgresCleanupManager.live ++
          PostgresWalMetrics.live())) >++>
      PostgresRevocationNotifications.live >++>
      SecureRandom.live >++>
      SecurityService.live >++>
      configSnapshot >++>
      CelEvaluator.live >++>
      // The three mode boundaries of #555. `EdgeConfig.validated` above has already refused a
      // mode none of them implements, so each selects by mode without a failure path of its own
      // to reach; the internal surface is mounted in `mountedRoutes`.
      ConfigurationSource.live >++>
      EdgeIdentity.live >++>
      DpopPolicyService.live >++>
      OverloadPolicyService.live >++>
      AccessLogPolicyService.live >++>
      RateLimitPolicyService.live >++>
      RateLimitPolicySource.fromCentral >++>
      EdgeRateLimiter.live >++>
      OAuthClientService.live >++>
      ResourceService.live >++>
      PermissionService.live >++>
      JwksService.live >++>
      TokenRevocationService.live >++>
      ClientCertificateFiles.live >++>
      SSOClient.live >++>
      DpopReplayGuard.shared >++>
      DpopVerifier.live >++>
      EdgeService.live >++>
      NativeAuthClient.live >++>
      NativeService.live >++>
      SSOClientWarmUp.live

  /** Keyed off this edge's private key; the records are prefixed with the edge id, so two edges
    * on one database keep their own. See [[ConfigSnapshot]].
    */
  private val configSnapshot: ZLayer[TransactorZIO & EdgeConfig, Nothing, ConfigSnapshot] =
    (PostgresConfigSnapshotRepository.live("edge_config_snapshots") ++ ZLayer.service[EdgeConfig]) >>>
      ZLayer.fromZIO:
        for
          repository <- ZIO.service[ConfigSnapshot.Repository]
          config <- ZIO.service[EdgeConfig]
          snapshot <- ConfigSnapshot.make(repository, config.privateKey.getEncoded, keyPrefix = s"${config.id}:")
        yield snapshot

  given DeriveConfig[versola.edge.model.EdgeId] = DeriveConfig[String].map(versola.edge.model.EdgeId(_))

  given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail(SecretConfig.secret)

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes32))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[java.security.PrivateKey] = DeriveConfig[String]
    .mapOrFail(SecretConfig.rsaPrivateKey)
