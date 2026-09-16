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
    // The origin clients reach this edge on. Used to build the `htu` a DPoP proof is checked
    // against (DpopVerifier) -- taken from configuration rather than from the request's own
    // `Host` or `X-Forwarded-*`, since those are set by whatever last handled the request and
    // trusting them would let a hop choose the URI a proof is validated against.
    edgeUrl: URL,
    configurationCacheRefreshInterval: Duration,
    revocation: EdgeConfig.Revocation = EdgeConfig.Revocation(),
    // RFC 9449 enforcement on proxied calls. Absent leaves it off entirely: a
    // `DPoP`-scheme request is then refused rather than half-checked, while a
    // token carrying `cnf.jkt` is still refused over `Bearer` regardless (see
    // EdgeService.extractAccessToken), so the downgrade this exists to close
    // cannot reopen just because the block is missing.
    dpop: Option[EdgeConfig.Dpop] = None,
):
  def internalUrl: URL = versolaInternalUrl.getOrElse(versolaUrl)

object EdgeConfig:

  case class Security(
      tokenEncryption: EdgeConfig.Security.TokenEncryption,
      edgeSessions: EdgeConfig.Security.EdgeSessions,
      /** Authorizes the non-prod `/service/configuration/sync` endpoint (see
        * ServiceController). Absent by default, which leaves that endpoint
        * unreachable -- configs written before it existed, and any environment
        * that never needs it, keep working unchanged; prod never needs it at all,
        * since the endpoint 404s there regardless of this. */
      internalSecret: Option[Secret] = None,
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

  /** RFC 9449 proof validation at the resource server.
    *
    * @param nonceSalt keys this edge's `DPoP-Nonce` space. §9 keeps the resource server's nonces
    *   separate from the authorization server's, so this is deliberately not auth's
    *   `dpop-nonces-secret`: a nonce minted by auth is not valid here.
    * @param allowedAlgorithms signing algorithms an incoming proof's `alg` may use. Kept
    *   independent of auth's list so a deployment can tighten the resource server without
    *   having to re-issue tokens.
    * @param iatLeeway maximum distance between a proof's `iat` and now, in either direction.
    *   Also the window a proof is remembered for, so it sizes the replay guard.
    * @param nonceTtl how long a nonce this edge issued stays acceptable.
    * @param requireNonce §9: whether every proof must carry a nonce this edge issued. On by
    *   default, and the default is the one to keep: a proxied API call is the thing a captured
    *   proof is actually worth replaying against, and the cost is one extra round trip per
    *   `nonce-ttl` per client. Per edge rather than per resource or per endpoint because a
    *   proof's `htm`/`htu` already bind it to one method and URL, so a nonce accepted across
    *   this edge's resources buys an attacker nothing that splitting the space would deny.
    *   Turning it off is a real reduction in what §9 gives you, not a tuning knob.
    */
  case class Dpop(
      nonceSalt: Secret.Bytes32,
      allowedAlgorithms: Set[versola.util.Dpop.Algorithm],
      iatLeeway: Duration,
      nonceTtl: Duration,
      requireNonce: Boolean = true,
  )

  object Dpop:
    /** The values a generated `dpop { }` block ships with (see `scripts/gen-env.scala`), for
      * callers that want them without restating each one. RFC 9449 §5 mandates `ES256`;
      * `PS256` is included for FAPI 2.0. `RS256` is verifiable but left out, matching auth's
      * own default -- a deployment can opt back in explicitly.
      */
    def default(nonceSalt: Secret.Bytes32): Dpop = Dpop(
      nonceSalt = nonceSalt,
      allowedAlgorithms = Set(versola.util.Dpop.Algorithm.ES256, versola.util.Dpop.Algorithm.PS256),
      iatLeeway = Duration.fromSeconds(60),
      nonceTtl = Duration.fromSeconds(600),
      requireNonce = true,
    )
