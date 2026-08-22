package versola.edge

import versola.edge.model.EdgeId
import versola.util.{EnvName, JWT, RsaKeyPair, Secret}
import zio.Duration
import zio.http.URL

import java.security.PrivateKey

case class EdgeConfig(
    id: EdgeId,
    keyId: String,
    privateKey: PrivateKey,
    security: EdgeConfig.Security,
    central: EdgeConfig.CentralConfig,
    // Public-facing: the browser is redirected here (SSOClient.authorizeUrl)
    // and it's what a token's `iss` claim is checked against
    // (EdgeService.sameOrigin) -- it must be an address a browser can
    // reach, not necessarily one this edge instance can reach itself.
    versolaUrl: URL,
    // Server-to-server: SSOClient's tokenUrl/userInfoUrl are real network
    // calls edge makes on its own, not through the user's browser. When
    // edge and auth aren't on the same network as the browser (e.g. each
    // in its own Docker container, "versola bootstrap local"), this is a
    // *different* address than versolaUrl. Optional (rather than a plain
    // URL defaulting to versolaUrl) because a case class default can't
    // reference a sibling parameter -- use the `internalUrl` accessor
    // below, which resolves the fallback. Absent for existing configs
    // that predate this field, which keeps them working unchanged
    // (correct wherever edge/auth/the browser all share one network, as
    // in prod and plain local dev).
    versolaInternalUrl: Option[URL] = None,
    configurationCacheRefreshInterval: Duration,
    revocation: EdgeConfig.Revocation = EdgeConfig.Revocation(),
):
  def internalUrl: URL = versolaInternalUrl.getOrElse(versolaUrl)

object EdgeConfig:

  case class Security(
      tokenEncryption: EdgeConfig.Security.TokenEncryption,
      edgeSessions: EdgeConfig.Security.EdgeSessions,
  )

  object Security:
    case class TokenEncryption(
        key: Secret.Bytes32,
    )

    case class EdgeSessions(
        secret: Secret.Bytes32,
        ttl: Duration,
    )

  case class CentralConfig(
      url: URL,
  )

  /** How many revocations are held in memory is central's to decide (it can see what an
    * edge's traffic warrants), so it is not here.
    *
    * @param reloadInterval how often the in-memory list is rebuilt from the database, which
    *                       is what drops expired entries and restores the in-memory-only path.
    */
  case class Revocation(
      reloadInterval: Duration = Duration.fromSeconds(600),
  )

