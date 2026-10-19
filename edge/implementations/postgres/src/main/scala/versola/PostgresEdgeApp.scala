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
  AuthorizationPresetsSyncClient,
  CentralSyncTokenService,
  ClientCertificateEnrollment,
  ClientCertificateFiles,
  DpopAlgorithmsSyncClient,
  DpopPolicySyncClient,
  EdgeConfig,
  EdgeController,
  EdgeService,
  JwksService,
  JwksSyncClient,
  OAuthClientService,
  OAuthClientsSyncClient,
  PermissionService,
  PermissionsSyncClient,
  PostgresDpopProofRepository,
  PostgresEdgeSessionRepository,
  PostgresLoginRepository,
  PostgresRevocationNotifications,
  PostgresRevocationRepository,
  ResourceService,
  ResourcesSyncClient,
  RolesSyncClient,
  SSOClient,
  ServiceController,
}
import versola.util.*
import versola.util.SequentialLayers.*
import versola.util.cel.CelEvaluator
import versola.util.http.VersolaApp
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
      CentralSyncTokenService &
      AuthorizationPresetsSyncClient &
      OAuthClientsSyncClient &
      ResourcesSyncClient &
      RolesSyncClient &
      PermissionsSyncClient &
      DpopAlgorithmsSyncClient &
      DpopPolicySyncClient &
      DpopPolicyService &
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

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    List(
      EdgeController.routes,
      NativeController.routes,
      ServiceController.routes,
    ).reduce(_ ++ _)

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
      CentralSyncTokenService.live >++>
      AuthorizationPresetsSyncClient.live >++>
      ClientCertificateEnrollment.live >++>
      OAuthClientsSyncClient.live >++>
      versola.edge.ClientPermissionsSyncClient.live >++>
      CelEvaluator.live >++>
      ResourcesSyncClient.live >++>
      RolesSyncClient.live >++>
      PermissionsSyncClient.live >++>
      DpopAlgorithmsSyncClient.live >++>
      DpopPolicySyncClient.live >++>
      DpopPolicyService.live >++>
      JwksSyncClient.live >++>
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
      NativeService.live

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
