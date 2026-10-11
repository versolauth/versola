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
  ClientCertificateFiles,
  ConfigurationSource,
  EdgeConfig,
  EdgeController,
  EdgeIdentity,
  EdgeService,
  InternalSurface,
  JwksService,
  OAuthClientService,
  PermissionService,
  PostgresDpopProofRepository,
  PostgresEdgeSessionRepository,
  PostgresLoginRepository,
  PostgresRevocationNotifications,
  PostgresRevocationRepository,
  ResourceService,
  SSOClient,
}
import versola.util.*
import versola.util.SequentialLayers.*
import versola.util.cel.CelEvaluator
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource, PostgresWalMetrics}
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
      EdgeIdentity &
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

  /** The surface every mode serves. The internal one is added by [[mountedRoutes]], which is
    * where the mode is known. */
  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    List(
      EdgeController.routes,
      NativeController.routes,
    ).reduce(_ ++ _)

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
      CelEvaluator.live >++>
      // The three mode boundaries of #555. `EdgeConfig.validated` above has already refused a
      // mode none of them implements, so each selects by mode without a failure path of its own
      // to reach; the internal surface is mounted in `mountedRoutes`.
      ConfigurationSource.live >++>
      EdgeIdentity.live >++>
      DpopPolicyService.live >++>
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

  given DeriveConfig[versola.edge.model.EdgeId] = DeriveConfig[String].map(versola.edge.model.EdgeId(_))

  given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail: str =>
      Secret.fromBase64Url(str)
        .left.map(message => zio.Config.Error.InvalidData(message = message))

  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  given DeriveConfig[java.security.PrivateKey] = DeriveConfig[String]
    .mapOrFail: str =>
      PrivateKeyUtil.parse(str, "RSA")
        .left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))

  private def parseBase64UrlSecret(newType: ByteArrayNewType.FixedLength)(str: String) =
    newType.fromBase64Url(str)
      .left.map(message => zio.Config.Error.InvalidData(message = message))
      .filterOrElse(
        _.length == newType.length,
        zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"),
      )
