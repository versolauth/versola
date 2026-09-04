
package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.typesafe.config.ConfigFactory
import versola.cleanup.PostgresCleanupManager
import versola.edge.login.LoginRepository
import versola.edge.revocation.{RevocationNotifications, RevocationRepository, TokenRevocationService}
import versola.edge.session.EdgeSessionRepository
import versola.edge.{AuthorizationPresetsSyncClient, CentralSyncTokenService, EdgeConfig, EdgeController, EdgeService, JwksService, JwksSyncClient, OAuthClientService, OAuthClientsSyncClient, PermissionService, PermissionsSyncClient, PostgresEdgeSessionRepository, PostgresLoginRepository, PostgresRevocationNotifications, PostgresRevocationRepository, ResourceService, ResourcesSyncClient, RolesSyncClient, SSOClient, ServiceController}
import versola.util.*
import versola.util.cel.CelEvaluator
import versola.util.http.VersolaApp
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.*
import zio.http.*
import zio.http.Client
import zio.http.Server.RequestStreaming
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.*

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
    SSOClient &
    EdgeService

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    List(
      EdgeController.routes,
      ServiceController.routes,
    ).reduce(_ ++ _)

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    parseConfig[EdgeConfig] >+>
      // `>+>` rather than `>>>`: PostgresRevocationNotifications needs the PostgresConfig the
      // transactor loaded, to open a connection of its own to park on LISTEN.
      (PostgresHikariDataSource.transactor(serviceName = Some("edge"), migrate = runMigrations) >+>
        (ZLayer.fromFunction(PostgresLoginRepository(_)) ++
          ZLayer.fromFunction(PostgresEdgeSessionRepository(_)) ++
          PostgresRevocationRepository.live ++
          PostgresCleanupManager.live)) >+>
      PostgresRevocationNotifications.live >+>
      SecureRandom.live >+>
      SecurityService.live >+>
      CentralSyncTokenService.live >+>
      AuthorizationPresetsSyncClient.live >+>
      OAuthClientsSyncClient.live >+>
      ResourcesSyncClient.live >+>
      RolesSyncClient.live >+>
      PermissionsSyncClient.live >+>
      JwksSyncClient.live >+>
      OAuthClientService.live >+>
      ResourceService.live >+>
      PermissionService.live >+>
      JwksService.live >+>
      TokenRevocationService.live >+>
      CelEvaluator.live >+>
      SSOClient.live >+>
      EdgeService.live


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
