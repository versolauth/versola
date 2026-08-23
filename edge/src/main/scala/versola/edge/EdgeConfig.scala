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

  /** @param reloadInterval how often the in-memory revocation list is reconciled with the
    *                       database. Nothing depends on it in normal operation — revocations
    *                       arrive by notification — so it is the backstop for a notification
    *                       lost some way a reconnect doesn't cover.
    * @param purgeInterval how often entries whose tokens have expired are dropped. Separate
    *                      from `reloadInterval` because it is the only thing bounding what a
    *                      replica holds and it needs no database, so it must not be tied to
    *                      the cadence of something that talks to one.
    * @param overlap how far back before the last row read a reconcile starts again.
    *                `revoked_at` is set when a row is written but the row appears when its
    *                transaction commits, so one that committed late would sit behind the
    *                cursor and never be read. Re-reading the window costs nothing: applying
    *                a revocation twice is applying it once.
    * @param batchSize how many rows a single read returns. Bounds what one read holds, not
    *                  what the replica ends up holding.
    */
  case class Revocation(
      reloadInterval: Duration = Duration.fromSeconds(600),
      purgeInterval: Duration = Duration.fromSeconds(60),
      overlap: Duration = Duration.fromSeconds(30),
      batchSize: Int = 50000,
  )

