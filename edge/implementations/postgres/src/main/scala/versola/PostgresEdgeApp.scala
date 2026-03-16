package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import com.typesafe.config.ConfigFactory
import versola.edge.{
  CompleteController,
  EdgeConfig,
  EdgeCredentialsService,
  EdgeSessionController,
  EdgeSessionRepository,
  EdgeSessionService,
  PostgresEdgeSessionRepository,
}
import versola.util.*
import versola.util.http.{HttpObservabilityConfig, VersolaApp}
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.*
import zio.http.*
import zio.http.Client
import zio.http.Server.RequestStreaming
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{ZLayer, *}

object PostgresEdgeApp extends VersolaApp("edge"):
  val environmentTag = Tag[Environment]

  override given Tag[Dependencies] = Tag[Dependencies]

  val diagnosticsConfig: Server.Config =
    Server.Config.default.port(8081)

  val serverConfig: Server.Config =
    Server.Config.default.port(9346)

  type Dependencies = EdgeSessionRepository &
    EdgeConfig &
    SecureRandom &
    EdgeSessionService &
    EdgeCredentialsService &
    SecurityService &
    Client

  override def routes: Routes[Dependencies & Tracing, Nothing] =
    List(
      CompleteController.routes,
      EdgeSessionController.routes,
    ).reduce(_ ++ _)

  val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing, Throwable, Dependencies] =
    parseConfig[EdgeConfig] >+>
      (PostgresHikariDataSource.transactor(serviceName = Some("edge"), migrate = true) >>>
        ZLayer.fromFunction(PostgresEdgeSessionRepository(_))) >+>
      SecureRandom.live >+>
      EdgeCredentialsService.live >+>
      Client.default >+>
      SecurityService.live >+>
      EdgeSessionService.live


  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes16))

  given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(parseBase64UrlSecret(Secret.Bytes32))

  given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private def parseBase64UrlSecret(newType: ByteArrayNewType.FixedLength)(str: String) =
    newType.fromBase64Url(str)
      .left.map(message => zio.Config.Error.InvalidData(message = message))
      .filterOrElse(
        _.length == newType.length,
        zio.Config.Error.InvalidData(message = s"Base64-encoded string must be ${newType.length} bytes. '$str' is '"),
      )
