package versola.edge

import versola.edge.model.EdgeId
import versola.util.{EnvName, JWT, RsaKeyPair, Secret}
import zio.{Duration, Task, ZIO, ZLayer}
import zio.http.URL

import java.io.FileInputStream
import java.security.PrivateKey
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.util.Using

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
    // Path to the PEM trust anchors the certificate auth's internal endpoint
    // presents is validated against. Required to authenticate a client by
    // RFC 8705 mutual TLS and used for nothing else: that is the one call edge
    // makes over a connection it must trust in both directions, since the
    // credential is the handshake itself.
    //
    // Absent refuses that call rather than defaulting, because the default
    // available is not a stricter one -- zio-http's ClientSSLConfig.Default is
    // Netty's InsecureTrustManagerFactory, which authenticates no server at
    // all. An internal endpoint rarely carries a publicly-trusted certificate,
    // so this names the one it presents -- a pin, not a CA: zio-http's client
    // performs no hostname verification (see SSOClient's own comment on
    // `internalTrust`), so a CA anchor would trust any certificate it has ever
    // issued, for any host, to stand in for this one. `EdgeConfig.validated`
    // refuses to start with anything this file's `BasicConstraints` mark as a
    // CA, so the file is a leaf certificate or nothing runs.
    versolaInternalTrustedCertificates: Option[String] = None,
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

  /** Fails startup rather than serving traffic that trusts more than the operator meant to
    * name. See `versolaInternalTrustedCertificates`'s own comment for why a CA there is a
    * hole zio-http gives this code no way to close afterwards -- so it is refused instead of
    * accepted and left for RFC 8705's own certificate-bound check to narrow later, which
    * applies to the *client* certificate, not to this one.
    */
  val validated: ZLayer[EdgeConfig, Throwable, EdgeConfig] =
    ZLayer.fromZIO(
      for
        config <- ZIO.service[EdgeConfig]
        _ <- ZIO.foreachDiscard(config.versolaInternalTrustedCertificates)(refuseCertificateAuthority)
      yield config,
    )

  private def refuseCertificateAuthority(path: String): Task[Unit] =
    ZIO.attemptBlocking {
      val factory = CertificateFactory.getInstance("X.509").nn
      val certificate = Using.resource(FileInputStream(path).nn): stream =>
        factory.generateCertificate(stream).nn.asInstanceOf[X509Certificate]
      // -1 means "not a CA" (see X509Certificate#getBasicConstraints); anything else,
      // including Int.MaxValue for an unconstrained path length, means it is one.
      if certificate.getBasicConstraints != -1 then
        throw IllegalArgumentException(
          s"versola-internal-trusted-certificates ($path) is a certificate authority, not a leaf. " +
            "zio-http's client performs no hostname verification on this connection, so trusting a " +
            "CA would accept any certificate it has issued -- for any host -- as this internal " +
            "endpoint. Point this at the specific certificate the endpoint presents instead.",
        )
    }

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

  /** RFC 9449 proof validation at the resource server: the parts that are facts about this
    * deployment rather than decisions about policy.
    *
    * What is *not* here is deliberate. The accepted signing algorithms (§5.1) are read off the
    * authorization server metadata document central holds, so `auth` and `edge` cannot come to
    * disagree about what a client may sign with; whether a nonce is required (§9) is per edge
    * in central, so it can be changed from the console rather than by redeploying the edge. See
    * `DpopAlgorithmsSyncClient` and `DpopPolicySyncClient`.
    *
    * @param nonceSalt keys this edge's `DPoP-Nonce` space. §9 keeps the resource server's nonces
    *   separate from the authorization server's, so this is deliberately not auth's
    *   `dpop-nonces-secret`: a nonce minted by auth is not valid here.
    * @param iatLeeway maximum distance between a proof's `iat` and now, in either direction.
    *   Also the window a proof is remembered for, so it sizes the replay guard.
    * @param nonceTtl how long a nonce this edge issued stays acceptable.
    */
  case class Dpop(
      nonceSalt: Secret.Bytes32,
      iatLeeway: Duration,
      nonceTtl: Duration,
  )

  object Dpop:
    /** The values a generated `dpop { }` block ships with (see `scripts/gen-env.scala`), for
      * callers that want them without restating each one.
      */
    def default(nonceSalt: Secret.Bytes32): Dpop = Dpop(
      nonceSalt = nonceSalt,
      iatLeeway = Duration.fromSeconds(60),
      nonceTtl = Duration.fromSeconds(600),
    )
