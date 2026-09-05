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
    configurationCacheRefreshInterval: Duration,
)

object CentralConfig:
  val centralClientId: ClientId = ClientId("central-admin")
  val defaultTenantId: TenantId = TenantId("default")

  case class AuthConfig(url: URL)

  case class PasskeyConfig(
      rpId: String,
      origins: List[String],
  )

  case class BootstrapConfig(
      login: String,
      adminUserId: java.util.UUID,
      redirectUris: List[String],
      edges: Option[List[CentralConfig.BootstrapConfig.EdgeSeed]],
      jwks: Option[Json.Obj],
      metadata: Option[Json.Obj],
      presets: Option[List[CentralConfig.BootstrapConfig.PresetSeed]],
      centralUrl: Option[String],
      /** Optional Base64Url-encoded secret for the central resource used by edge-to-central calls.
        * When absent, bootstrap generates a random secret before storing it in the central resource record.
        */
      resourceSecret: Option[Secret],
      /** OP-initiated front-channel logout endpoint notified so the browser-facing edge can
        * clear its session cookie when this session is logged out elsewhere. Must be a
        * publicly reachable URL (loaded by the browser), not necessarily edge's own address —
        * by default the edge is path-routed on the same origin as auth (see deploy.md), so
        * this is typically "$authUrl/logout/frontchannel".
        */
      frontChannelLogoutUri: Option[String],
      passkey: CentralConfig.PasskeyConfig,
      /** Internal URL of auth's additional listener (Account Settings). Used to seed
        * the internal "auth" resource record that proxies Account Settings through edge.
        */
      authAdditionalUrl: URL,
      /** Base64Url-encoded secret shared with auth's additional listener, used to
        * authenticate the seeded "auth" resource record. Always required — unlike
        * the central resource secret, this is not auto-generated, since auth also
        * needs to be configured with the same value out of band.
        */
      authResourceSecret: Secret,
  )

  object BootstrapConfig:
    case class EdgeSeed(id: EdgeId, publicKeyJwk: Json.Obj)

    /** Seed data for an authorization preset.
      *
      * `cookieDomain` / `cookiePath` scope the EDGE_SESSION cookie that edge
      * sets after login. Both default to None, which leaves the cookie at the
      * host origin and path "/" — i.e. shared by everything on the domain.
      * Setting a path confines the session to one application, so several apps
      * behind the same edge can hold independent sessions; that only works if
      * every URL the app touches (its assets, its API, its permissions lookup)
      * lives under that path, since a cookie is never sent above its own path.
      */
    case class PresetSeed(
        id: String,
        description: String,
        redirectUri: String,
        postLoginRedirectUri: String,
        postLogoutRedirectUri: Option[String] = None,
        cookieDomain: Option[String] = None,
        cookiePath: Option[String] = None,
    )

  case class UserOutboxConfig(
      pollInterval: Duration,
      batchSize: Int,
      lease: Duration,
      maxBackoff: Duration,
      maxAttempts: Int,
  )
