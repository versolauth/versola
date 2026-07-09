package versola.central

import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.tenants.TenantId
import versola.util.Secret
import zio.Duration
import zio.http.URL
import zio.json.ast.Json

import javax.crypto.SecretKey

case class CentralConfig(
    bootstrap: Option[CentralConfig.BootstrapConfig],
    clientSecretsSecret: Secret,
    secretKey: SecretKey,
    auth: CentralConfig.AuthConfig,
    userOutbox: CentralConfig.UserOutboxConfig,
)

object CentralConfig:
  val centralClientId: ClientId = ClientId("central-admin")
  val defaultTenantId: TenantId = TenantId("default")

  case class AuthConfig(url: URL)

  case class BootstrapConfig(
      login: String,
      adminUserId: java.util.UUID,
      redirectUris: List[String],
      edges: Option[List[CentralConfig.BootstrapConfig.EdgeSeed]],
      jwks: Option[Json.Obj],
      presets: Option[List[CentralConfig.BootstrapConfig.PresetSeed]],
      centralUrl: Option[String],
  )

  object BootstrapConfig:
    case class EdgeSeed(id: EdgeId, publicKeyJwk: Json.Obj)

    /** Seed data for an authorization preset. */
    case class PresetSeed(
        id: String,
        description: String,
        redirectUri: String,
        postLoginRedirectUri: String,
    )

  case class UserOutboxConfig(
      pollInterval: Duration,
      batchSize: Int,
      lease: Duration,
      maxBackoff: Duration,
      maxAttempts: Int,
  )
