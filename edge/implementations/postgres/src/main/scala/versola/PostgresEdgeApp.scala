package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.typesafe.config.ConfigFactory
import versola.cleanup.PostgresCleanupManager
import versola.edge.login.LoginRepository
import versola.edge.session.EdgeRefreshTokenRepository
import versola.edge.{AuthorizationPresetsSyncClient, CentralSyncTokenService, EdgeConfig, EdgeController, EdgeService, JwksService, JwksSyncClient, OAuthClientService, OAuthClientsSyncClient, PermissionService, PermissionsSyncClient, PostgresEdgeRefreshTokenRepository, PostgresLoginRepository, ResourceService, ResourcesSyncClient, RolesSyncClient, SSOClient}
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
    EdgeRefreshTokenRepository &
    JwksService &
    SSOClient &
    EdgeService

  override def routes: Routes[Dependencies & Tracing, Throwable] =
    List(
      EdgeController.routes,
    ).reduce(_ ++ _)

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    parseConfig[EdgeConfig] >+>
      (PostgresHikariDataSource.transactor(serviceName = Some("edge"), migrate = true) >>>
        (ZLayer.fromFunction(PostgresLoginRepository(_)) ++
          ZLayer.fromFunction(PostgresEdgeRefreshTokenRepository(_)) ++
          PostgresCleanupManager.live)) >+>
      SecureRandom.live >+>
      SecurityService.live >+>
      CentralSyncTokenService.live >+>
      AuthorizationPresetsSyncClient.live >+>
      OAuthClientsSyncClient.live >+>
      ResourcesSyncClient.live >+>
      RolesSyncClient.live >+>
      PermissionsSyncClient.live >+>
      JwksSyncClient.live >+>
      OAuthClientService.live(zio.Schedule.spaced(5.minute)) >+>
      ResourceService.live(zio.Schedule.spaced(5.minute)) >+>
      PermissionService.live(zio.Schedule.spaced(5.minute)) >+>
      JwksService.live(zio.Schedule.spaced(5.minute)) >+>
      CelEvaluator.live >+>
      SSOClient.live >+>
      EdgeService.live


  given DeriveConfig[versola.edge.model.EdgeId] = DeriveConfig[String].map(versola.edge.model.EdgeId(_))

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
