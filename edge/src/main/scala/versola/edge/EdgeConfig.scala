package versola.edge

import versola.edge.model.EdgeId
import versola.util.http.ClientIpSource
import versola.util.revocation.RevocationConfig
import versola.util.{EnvName, JWT, RsaKeyPair, Secret}
import zio.config.magnolia.DeriveConfig
import zio.http.{ClientSSLConfig, Scheme, URL}
import zio.{Config, Duration, Task, ZIO, ZLayer}

import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.PrivateKey
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.jdk.CollectionConverters.*
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
    // RFC 8705 mutual TLS: that is the one call edge makes over a connection
    // it must trust in both directions, since the credential is the handshake
    // itself. Every other call to the internal endpoint (a secret or an
    // assertion in the request) is made over the same pin when it is set, and
    // over the JVM's trust store when it is not (SSOClient's `internalClient`).
    //
    // Absent refuses the certificate call rather than defaulting, because the
    // default available is not a stricter one -- zio-http's ClientSSLConfig.Default is
    // Netty's InsecureTrustManagerFactory, which authenticates no server at
    // all. An internal endpoint rarely carries a publicly-trusted certificate,
    // so this names the one it presents -- a pin, not a CA: zio-http's client
    // performs no hostname verification (see SSOClient's own comment on
    // `internalTrust`), so a CA anchor would trust any certificate it has ever
    // issued, for any host, to stand in for this one. `EdgeConfig.validated`
    // refuses to start with anything this file's `BasicConstraints` mark as a
    // CA, so the file is a leaf certificate or nothing runs.
    //
    // A set so the pin can be rotated without a window in which edge rejects auth: publish
    // `{old leaf, new leaf}`, cut auth over, then drop the old one. A peer matching any entry
    // is accepted; every entry is held to the leaf-only rule above. Empty is "absent".
    versolaInternalTrustedCertificates: Set[String] = Set.empty,
    // The origin clients reach this edge on. Used to build the `htu` a DPoP proof is checked
    // against (DpopVerifier) -- taken from configuration rather than from the request's own
    // `Host` or `X-Forwarded-*`, since those are set by whatever last handled the request and
    // trusting them would let a hop choose the URI a proof is validated against.
    edgeUrl: URL,
    configurationCacheRefreshInterval: Duration,
    // How this edge keeps its list of revoked access tokens current, and whether it also pulls them from auth.
    revocation: EdgeConfig.Revocation = EdgeConfig.Revocation(),
    // RFC 9449 enforcement on proxied calls. Absent leaves it off entirely: a
    // `DPoP`-scheme request is then refused rather than half-checked, while a
    // token carrying `cnf.jkt` is still refused over `Bearer` regardless (see
    // EdgeService.extractAccessToken), so the downgrade this exists to close
    // cannot reopen just because the block is missing.
    dpop: Option[EdgeConfig.Dpop] = None,
    // The `/native/*` back channel for mobile apps under FAPI 2.0 (#420). Absent leaves those
    // endpoints answering 404 for every client -- nothing else about this edge changes.
    native: Option[EdgeConfig.Native] = None,
    // What this edge is to the operator's installation (#555): see [[EdgeMode]]. Defaults to
    // the only mode that existed before the key did, so every configuration written without
    // it keeps running as the first-party edge it always was.
    mode: EdgeMode = EdgeMode.FirstParty,
    // The listener that serves `/resources/*` to callers that authenticate their token by the
    // certificate of the handshake (RFC 8705 §3). Absent serves none: the plain listener
    // cannot see a caller's certificate, so it keeps refusing certificate-bound tokens.
    mutualTls: Option[EdgeConfig.MutualTls] = None,
    // Where the rate limits (R1) read a caller's address from: see [[ClientIpSource]]. `socket` unless the deployment says its
    // proxy sets the header, because a forwarded header is only as trustworthy as the network path that guards it.
    clientIp: ClientIpSource = ClientIpSource.Socket,
    clientIpHeader: String = "X-Real-IP",
    // How many edge pods share the traffic, for the per-pod share of a rate limit: `dns:<name>`, see [[ReplicaCounter]]. Absent: the `replicas` of
    // the policy set in central.
    replicasFrom: Option[String] = None,
    // What only a partner edge (#564) is told: who its first administrators are, where its console's files are, and how long
    // an invite lives. Refused outright on a first-party edge, where none of it means anything.
    partner: EdgeConfig.Partner = EdgeConfig.Partner(),
):
  def internalUrl: URL = versolaInternalUrl.getOrElse(versolaUrl)

  /** Where this edge reads the configuration `name` from: the first-party sync surface of central, or the partner's own
    * (`/edge/partner/sync`, #962), which answers with the same shapes for the rows of this edge's partner only. */
  def syncUrl(name: String): URL =
    mode match
      case EdgeMode.FirstParty => central.url / "edge" / "sync" / name
      case EdgeMode.Partner => central.url / "edge" / "partner" / "sync" / name

  /** Where this edge reaches the partner surface of central that is not a sync (`/me`, the management routes of the console). */
  def partnerUrl: URL = central.url / "edge" / "partner"

/** How this edge relates to the operator's installation, and so where it gets its
  * configuration from and how it proves itself to auth (#555). The mode selects an
  * implementation at each of three boundaries -- [[ConfigurationSource]], [[EdgeIdentity]]
  * and [[InternalSurface]] -- and nothing that consumes configuration, calls auth or serves a
  * route looks at it otherwise.
  */
enum EdgeMode(
    /** The spelling the `mode` key takes, kebab-case like every other key and value in the file. */
    val name: String,
):
  /** The operator's own edge, an internal component of the installation: configuration synced
    * from central, authenticated to auth by [[versola.util.EdgeAssertion]] under the key
    * central registered for it, and an internal surface reachable from inside the cell. */
  case FirstParty extends EdgeMode("first-party")

  /** An edge run by a partner on infrastructure the operator does not control (#564): configured by the narrow
    * `/edge/partner` surface of central over mutual TLS, scoped to the partner by the edge certificate and to nothing
    * else, authenticating the partner's clients to auth as ordinary clients (never vouching for a token as an edge), with
    * no internal surface, and serving the partner's console and `/partner/api` of its own. */
  case Partner extends EdgeMode("partner")

object EdgeMode:
  given DeriveConfig[EdgeMode] = DeriveConfig[String].mapOrFail: value =>
    values.find(_.name == value)
      .toRight(Config.Error.InvalidData(message = s"mode must be one of ${values.map(_.name).mkString(", ")}, not '$value'"))

object EdgeConfig:

  /** The `revocation` block: the keys of [[RevocationConfig]] (the list every replica holds, shared with auth) and
    * `feed`, the pull of auth's revocations that only edge has.
    *
    * The cache keys are repeated rather than inherited so that the one block reads as it always did; their
    * defaults are [[RevocationConfig]]'s own.
    */
  case class Revocation(
      reloadInterval: Duration = RevocationConfig().reloadInterval,
      purgeInterval: Duration = RevocationConfig().purgeInterval,
      overlap: Duration = RevocationConfig().overlap,
      batchSize: Int = RevocationConfig().batchSize,
      feed: RevocationFeed = RevocationFeed(),
  ):
    def cache: RevocationConfig =
      RevocationConfig(reloadInterval = reloadInterval, purgeInterval = purgeInterval, overlap = overlap, batchSize = batchSize)

  /** The pull of revocations from auth (`GET /revocations` on its mutual-TLS listener, #560), the guarantee for a
    * revocation whose push to `back_channel_logout_uri` was lost or never sent.
    *
    * Off by default: an edge that does not set `enabled` behaves as it did, on the push alone. It needs the `native`
    * block, which names auth's mutual-TLS listener and pins it; without one the feed is not started and a warning says why.
    *
    * @param pollInterval how often each pod looks, jittered. The pod that finds the lease free reads.
    * @param lease how long a pod owns the right to read after taking it, extended while it works. A pod that dies hands
    *   the right on this long after; it must outlast `timeout`, so a call that is slow but not dead does not lose it.
    * @param pageSize rows asked of auth per request, at most [[MaxFeedPageSize]] (what auth serves).
    * @param timeout how long one request to auth may take.
    */
  case class RevocationFeed(
      enabled: Boolean = false,
      pollInterval: Duration = Duration.fromSeconds(1),
      lease: Duration = Duration.fromSeconds(5),
      pageSize: Int = 5000,
      timeout: Duration = Duration.fromSeconds(3),
  )

  /** The most rows auth serves in a page (`RevocationFeedController.MaxLimit`): a larger request is served this many,
    * and the short-page rule that ends a read would then end it early. */
  val MaxFeedPageSize: Int = 10000

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
        _ <- ZIO.foreachDiscard(config.versolaInternalTrustedCertificates)(
          refuseCertificateAuthority("versola-internal-trusted-certificates", _),
        )
        _ <- ZIO.foreachDiscard(config.native.toList)(native =>
          refuseEmptyPins("native.trusted-certificates", native.trustedCertificates) *>
            ZIO.foreachDiscard(native.trustedCertificates)(
              refuseCertificateAuthority("native.trusted-certificates", _),
            ),
        )
        _ <- refuseNativeWithoutDpop(config)
        _ <- refuseUnusableFeed(config.revocation.feed)
        _ <- refuseMisconfiguredMode(config)
        _ <- refuseCentralWithoutMutualTls(config)
        _ <- ZIO.foreachDiscard(config.replicasFrom)(spec => ZIO.fromEither(ReplicaCounter.parse(spec)).mapError(IllegalArgumentException(_)))
      yield config,
    )

  /** A feed whose numbers cannot work is refused at startup rather than found as a cycle that never completes. */
  private def refuseUnusableFeed(feed: RevocationFeed): Task[Unit] =
    val problems = List(
      Option.when(feed.pollInterval.isZero || feed.pollInterval.isNegative)("poll-interval must be positive"),
      Option.when(feed.timeout.isZero || feed.timeout.isNegative)("timeout must be positive"),
      Option.when(feed.lease.compareTo(feed.timeout) <= 0)(
        "lease must be longer than timeout, or a pod loses the lease while its own request is still allowed to run",
      ),
      Option.when(feed.pageSize < 1 || feed.pageSize > MaxFeedPageSize)(s"page-size must be between 1 and $MaxFeedPageSize"),
    ).flatten
    ZIO.fail(IllegalArgumentException(s"revocation.feed: ${problems.mkString("; ")}")).when(problems.nonEmpty).unit

  /** What a mode needs and what it must not have (#564). Refused here, at the one place every mode is read, rather than
    * wherever the first boundary happens to be built.
    *
    * A partner edge runs where the operator has no control, so what it holds is only what central gave it: its certificate
    * comes from central (`central.identity`, renewed from the 14-day certificate the partner's group gets), never one
    * deployed with it; and it has no `security.internal-secret`, because that secret authorizes the non-production
    * `/service` surface, which a partner edge does not have. That the mode agrees with the kind of the edge's group in
    * central is not known until the edge has spoken to central: `PartnerState` checks it with the first `/edge/partner/me`.
    * It is a guard against a misconfiguration, not a security control (central decides the kind and scopes every answer by the
    * edge's certificate).
    */
  private def refuseMisconfiguredMode(config: EdgeConfig): Task[Unit] =
    def invalid(message: String): Task[Unit] = ZIO.fail(IllegalArgumentException(message))
    config.mode match
      case EdgeMode.FirstParty =>
        invalid("partner.admins and partner.console-directory are for a partner edge: set mode = \"partner\" or remove them")
          .when(config.partner.admins.nonEmpty || config.partner.consoleDirectory.isDefined)
          .unit *>
          // a partner edge keeps what its enrollment answered here (`enrolled.json`); the same directory under mode = first-party would be a
          // partner's identity presented as the operator's own, which central refuses on every sync and this refuses at the start, with the reason
          ZIO.foreachDiscard(config.central.identity): identity =>
            ZIO.attemptBlocking(Files.exists(Path.of(identity.directory, "enrolled.json"))).flatMap: enrolled =>
              invalid(
                s"central.identity.directory ${identity.directory} holds the enrollment of a partner edge (enrolled.json): " +
                  "start it with mode = \"partner\"; an edge of the operator enrols with its own token in its own directory",
              ).when(enrolled)
      case EdgeMode.Partner =>
        if config.central.identity.isEmpty then
          invalid(
            "mode = partner needs central.identity: a partner edge holds a certificate central issued to it (enrolled with its " +
              "enrollment token and renewed by itself), not one it was deployed with",
          )
        else if config.security.internalSecret.isDefined then
          invalid(
            "mode = partner refuses security.internal-secret: it authorizes the /service/* surface, which a partner edge does not serve",
          )
        else if config.partner.admins.exists(_.isBlank) then invalid("partner.admins names a blank phone number or email")
        else if config.central.identity.flatMap(_.enrollment).exists(_.url.isDefined) then
          invalid("central.identity.enrollment.url is not set on a partner edge: the enrollment token carries the address")
        else ZIO.unit

  /** The native flow is DPoP end to end -- every client it serves is registered
    * `dpopBoundAccessTokens`, `/native/start` verifies and replay-guards the device's proof,
    * and both windows come from the `dpop` block. Without it this edge would verify those
    * proofs against a default nobody chose and then refuse, as `DpopVerifier.Error.NotConfigured`,
    * every API call made with the very tokens the flow issued. Refused at startup rather than
    * discovered one request at a time.
    */
  private def refuseNativeWithoutDpop(config: EdgeConfig): Task[Unit] =
    ZIO.when(config.native.isDefined && config.dpop.isEmpty)(
      ZIO.fail(
        IllegalArgumentException(
          "a `native` block needs a `dpop` block: the native endpoints verify and replay-guard " +
            "the device's DPoP proofs on its windows, and every token they issue is DPoP-bound, " +
            "so an edge without one serves a flow whose tokens it will then refuse.",
        ),
      ),
    ).unit

  /** Central serves the edge surface only on its mutual-TLS listener, over HTTPS, to an edge
    * that presents its registered certificate; and zio-http authenticates a server only by a
    * pin (see `versolaInternalTrustedCertificates`). Anything less is a configuration central
    * cannot be reached with, refused here rather than at the first sync.
    */
  private def refuseCentralWithoutMutualTls(config: EdgeConfig): Task[Unit] =
    config.central.mutualTls match
      case None =>
        ZIO.fail(IllegalArgumentException(
          "central.mutual-tls is required: central serves the edge surface only over mutual TLS, " +
            "to an edge that presents the certificate registered for it",
        ))
      case Some(mtls) =>
        ZIO.fail(IllegalArgumentException(s"central.url must be https, got '${config.central.url.encode}'"))
          .unless(config.central.url.scheme.contains(Scheme.HTTPS)) *>
          refuseCertificateSource(config.central, mtls) *>
          refuseEmptyPins("central.mutual-tls.trusted-certificates", mtls.trustedCertificates) *>
          ZIO.foreachDiscard(mtls.trustedCertificates)(
            refuseCertificateAuthority("central.mutual-tls.trusted-certificates", _),
          )

  /** The certificate this edge presents to central comes from exactly one place: the files whatever deployed the
    * edge provides (`mutual-tls.certificate` and `private-key`, together), or central itself (`identity`). Both, or
    * neither, is a configuration in which it is unclear which certificate is presented. */
  private def refuseCertificateSource(central: CentralConfig, mtls: CentralMutualTls): Task[Unit] =
    val static = mtls.certificate.isDefined || mtls.privateKey.isDefined
    def invalid(message: String) = ZIO.fail(IllegalArgumentException(message))
    if mtls.certificate.isDefined != mtls.privateKey.isDefined then
      invalid("central.mutual-tls.certificate and central.mutual-tls.private-key are given together or not at all")
    else if static && central.identity.isDefined then
      invalid("central.identity and central.mutual-tls.certificate both name this edge's certificate: give one")
    else if !static && central.identity.isEmpty then
      invalid("the certificate this edge presents to central is not named: set central.mutual-tls.certificate and private-key, or central.identity")
    else
      ZIO.foreachDiscard(central.identity.flatMap(_.enrollment)): enrollment =>
        enrollment.url match
          case Some(url) => invalid("central.identity.enrollment.url must be https").unless(url.scheme.contains(Scheme.HTTPS))
          case None => ZIO.unit

  private def refuseEmptyPins(name: String, pins: Set[String]): Task[Unit] =
    ZIO.fail(IllegalArgumentException(s"$name must name at least one certificate")).when(pins.isEmpty).unit

  /** Every certificate in the file is checked, not just the first: a PEM may hold several
    * blocks, and Netty trusts all of them.
    */
  private def refuseCertificateAuthority(name: String, path: String): Task[Unit] =
    ZIO.attemptBlocking {
      val factory = CertificateFactory.getInstance("X.509").nn
      val certificates = Using.resource(FileInputStream(path).nn): stream =>
        factory.generateCertificates(stream).nn.asScala.toList
      if certificates.isEmpty then
        throw IllegalArgumentException(s"$name ($path) holds no certificate")
      certificates.foreach: certificate =>
        // -1 means "not a CA" (see X509Certificate#getBasicConstraints); anything else,
        // including Int.MaxValue for an unconstrained path length, means it is one.
        if certificate.asInstanceOf[X509Certificate].getBasicConstraints != -1 then
          throw IllegalArgumentException(
            s"$name ($path) is a certificate authority, not a leaf. " +
              "zio-http's client performs no hostname verification on this connection, so trusting a " +
              "CA would accept any certificate it has issued -- for any host -- as this internal " +
              "endpoint. Point this at the specific certificate the endpoint presents instead.",
          )
    }

  /** The client-side trust for a set of pins. zio-http's `FromCertFile` takes one path but
    * Netty loads every certificate in it, so several pins are concatenated into one file
    * (written once, removed on exit) rather than needing a different `ClientSSLConfig`.
    */
  def pinnedTrust(paths: Set[String]): ClientSSLConfig =
    paths.toList.sorted match
      case single :: Nil => ClientSSLConfig.FromCertFile(single)
      case many =>
        val bundle = Files.createTempFile("versola-pins", ".pem").nn
        bundle.toFile.nn.deleteOnExit()
        Files.write(bundle, many.flatMap(path => Files.readAllLines(Path.of(path)).nn.asScala :+ "").mkString("\n").getBytes(StandardCharsets.UTF_8))
        ClientSSLConfig.FromCertFile(bundle.toString)

  /** The partner's own configuration (#564), meaningful in [[EdgeMode.Partner]] only.
    *
    * @param admins the first administrators of the partner's console: each a phone number (`+7701...`) or an email address.
    *   Applied as invites at every start (see `StaffService.applyAdmins`), so the first sign-in of such a person is admitted as
    *   `admin`; nothing is looked up at the operator.
    * @param consoleDirectory where the static files of the partner console are (`partner.html`, `versola-partner.js`),
    *   built from `central-ui`'s second entry and served under `/partner/`. Absent serves none: `/partner/` answers 404 and the
    *   API still works.
    * @param inviteTtl how long an invite lives, from the moment an administrator makes it and from every start for `admins`.
    */
  case class Partner(
      admins: List[String] = Nil,
      consoleDirectory: Option[String] = None,
      inviteTtl: Duration = Duration.fromSeconds(7L * 24 * 3600),
  )

  /** Edge's own mutual-TLS listener (`MPORT`), shaped like auth's (`CoreConfig.MutualTlsConfig`).
    *
    * @param certificate PEM path to the certificate this listener presents.
    * @param privateKey PEM path to its key, unencrypted PKCS#8.
    * @param trustedCertificates PEM path to the anchors a caller's chain is validated against.
    *   Required, with no default: left unset Netty trusts the JDK's store, and every
    *   publicly-trusted CA would vouch for callers of this listener.
    * @param externalUrl the address callers reach this listener at, for the operator's own
    *   documentation and tooling; a deployment behind a NAT or a TCP-passthrough load balancer
    *   binds on one address and publishes another.
    */
  case class MutualTls(
      certificate: String,
      privateKey: String,
      trustedCertificates: String,
      externalUrl: URL,
  )

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

  /** @param url central's mutual-TLS listener (`MPORT`), the only place the edge surface is
    *   served (#554). It is reachable from outside the cluster, so the connection is
    *   authenticated both ways.
    * @param mutualTls the certificate this edge presents there and the pin it holds central's
    *   own certificate to. Absent is refused at startup by [[validated]]: central would turn
    *   this edge away in the handshake, so every sync would fail one request at a time instead.
    * @param identity set when central issues this edge's certificate (see [[CentralIdentity]])
    *   instead of whatever deployed the edge providing one in `mutualTls.certificate`.
    */
  case class CentralConfig(
      url: URL,
      mutualTls: Option[CentralMutualTls] = None,
      identity: Option[CentralIdentity] = None,
  )

  /** @param certificate PEM path to the client certificate this edge presents to central: the one
    *   cert-manager or `mtls-init` issued. Absent when central issues it ([[CentralIdentity]]).
    * @param privateKey PEM path to its key, unencrypted PKCS#8. Present with `certificate`, or neither.
    * @param trustedCertificates PEM paths of central's own leaf certificate(s), a pin and not a
    *   CA for the reasons given on `versolaInternalTrustedCertificates`; several so the pin can
    *   be rotated.
    */
  case class CentralMutualTls(
      certificate: Option[String] = None,
      privateKey: Option[String] = None,
      trustedCertificates: Set[String],
  )

  /** The certificate of this edge when central issues it, and keeps it renewed.
    *
    * @param directory where the key and the certificate chain are kept between restarts (`edge.key`,
    *   `edge.crt`, owner-only). Without it an edge would need a new enrollment token on every restart.
    * @param group the group of edges this edge was registered in. The request for the certificate must name
    *   it, and central refuses one that names another, so this is a statement the operator makes when
    *   deploying the edge and central checks; it is not what makes the edge a member.
    * @param enrollment how the very first certificate is had; absent on an edge that already has one in
    *   `directory`.
    */
  case class CentralIdentity(
      directory: String,
      group: String = "default",
      enrollment: Option[CentralEnrollment] = None,
  )

  /** @param url central's public name for enrollment (plain TLS, no client certificate), `https`.
    * @param tokenFile the one-time token an operator created for this edge, in a file so that it is not
    *   in the environment or the config. Read once, when there is no certificate yet.
    */
  case class CentralEnrollment(
      url: Option[URL] = None,
      tokenFile: Option[String] = None,
      token: Option[String] = None,
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

  /** The native-app back channel (#420): edge authenticates to auth as a native client with
    * the `tls_client_auth` certificate central synced, straight on auth's own mutual-TLS
    * listener (`MPORT`, #417) -- no TLS terminator in between -- while the device's DPoP key
    * is the only sender constraint on the tokens.
    *
    * @param authMutualTlsUrl how this edge reaches auth's mutual-TLS listener.
    * @param authMutualTlsExternalUrl what auth calls that listener (its `mutual-tls.external-url`,
    *   the `mtls_endpoint_aliases` origin). It is the origin of the `htu` a device proof for
    *   `/token` must carry, since edge forwards the proof unchanged to that listener. Defaults
    *   to [[authMutualTlsUrl]], which is right wherever edge and the listener share a network.
    * @param trustedCertificates PEM paths of the certificates the listener may present -- pins, not
    *   CAs (several only to overlap a rotation, see `versolaInternalTrustedCertificates`), for the same reason as `versolaInternalTrustedCertificates`: zio-http's client does
    *   no hostname verification. `EdgeConfig.validated` refuses a CA here.
    * @param blobKey the AES-256-GCM key sealing the stateless blob `/native/start` hands the app
    *   in place of a stored login record. Its own key, not `tokenEncryption`'s.
    * @param blobTtl how long a blob can be redeemed after `/native/start`. Bounded above by the
    *   single-use `request_uri` and code in any case.
    * @param authIssuer the `iss` auth's authorization responses carry -- its `jwt.issuer`
    *   (`CoreConfig.JwtConfig.issuer`), a setting of auth's own and not derived from
    *   [[versolaUrl]]. Absent defaults to `versolaUrl` via [[issuer]] below, which is right
    *   only where the two happen to be configured alike; naming this here is what lets
    *   `/native/complete`'s RFC 9207 check compare against the value auth actually emits
    *   rather than assume it.
    */
  case class Native(
      authMutualTlsUrl: URL,
      authMutualTlsExternalUrl: Option[URL] = None,
      trustedCertificates: Set[String],
      blobKey: Secret.Bytes32,
      blobTtl: Duration = Duration.fromSeconds(600),
      authIssuer: Option[URL] = None,
  ):
    def externalUrl: URL = authMutualTlsExternalUrl.getOrElse(authMutualTlsUrl)

    /** RFC 9207 `iss` comparison target: [[authIssuer]] if this deployment names one, else
      * `versolaUrl` -- see [[authIssuer]]'s own comment for when that fallback is wrong. */
    def issuer(versolaUrl: URL): URL = authIssuer.getOrElse(versolaUrl)

  object Dpop:
    /** The values a generated `dpop { }` block ships with (see `scripts/gen-env.scala`), for
      * callers that want them without restating each one.
      */
    def default(nonceSalt: Secret.Bytes32): Dpop = Dpop(
      nonceSalt = nonceSalt,
      iatLeeway = Duration.fromSeconds(60),
      nonceTtl = Duration.fromSeconds(600),
    )
