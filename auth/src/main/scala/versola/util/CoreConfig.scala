package versola.util

import com.nimbusds.jose.jwk.{JWK, JWKSet, RSAKey}
import org.apache.commons.codec.digest.Blake3
import versola.util.revocation.RevocationConfig
import zio.Duration
import zio.http.{Method, URL}

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import scala.util.Try

case class CoreConfig(
    security: CoreConfig.Security,
    jwt: CoreConfig.JwtConfig,
    central: CoreConfig.CentralSyncConfig,
    bootstrap: Option[CoreConfig.BootstrapConfig],
    otpProvider: Option[CoreConfig.OtpProvider],
    smtp: Option[CoreConfig.SmtpConfig],
    configurationCacheRefreshInterval: Duration,
    par: Option[CoreConfig.ParConfig],
    dpop: Option[CoreConfig.DpopConfig],
    argon2: Option[Argon2Config],
    mutualTls: Option[CoreConfig.MutualTlsConfig],
    est: Option[CoreConfig.EstConfig] = None,
    // Limits on the channel that is not mutual TLS (#745); absent, the defaults of [[ClientAuthThrottle.Settings]] apply.
    limits: Option[versola.oauth.clientauth.ClientAuthThrottle.Settings] = None,
    // The in-memory list of revoked access tokens (#729); absent, the defaults of [[RevocationConfig]] apply.
    revocation: Option[RevocationConfig] = None,
    /** The cell this deployment is, when it is one of several (versolauth/versola#547 §6.2):
      * written at the front of the opaque values it issues and as the `cell` JOSE header of
      * the tokens and security event tokens it signs, so a router can find their home cell
      * without a lookup. Absent, nothing is prefixed or claimed, and values issued before
      * a cell id was configured are accepted either way.
      */
    cellId: Option[RoutingKey.CellId] = None,
):
  def parOrDefault: CoreConfig.ParConfig = par.getOrElse(CoreConfig.ParConfig.default)

  def dpopOrDefault: CoreConfig.DpopConfig = dpop.getOrElse(CoreConfig.DpopConfig.default)

  def argon2OrDefault: Argon2Config = argon2.getOrElse(Argon2Config.default)

  def revocationOrDefault: RevocationConfig = revocation.getOrElse(RevocationConfig())

  /** The address this deployment answers `path` on, for whichever of its two listeners a
    * request arrived at -- RFC 9449 §4.3's `htu` is the one thing that has to know the
    * difference.
    *
    * `/token` and `/userinfo` are served twice: once at the issuer, once at
    * [[CoreConfig.MutualTlsConfig.externalUrl]], which RFC 8705 §5 has the metadata advertise
    * as `mtls_endpoint_aliases`. A client that follows an alias calls a different authority,
    * so §4.3 obliges it to stamp that authority into `htu` -- and holding both listeners to
    * the issuer's address, as this did before the alias existed, would reject a proof that is
    * correct.
    *
    * Both sides of the choice are configured values, never the inbound request's own `Host`:
    * the point of deriving `htu`'s expectation rather than reading it is that a forwarded
    * host header cannot be used to make a proof minted for some other origin validate here.
    * What the request decides is only *which* of the two configured addresses applies.
    */
  def endpointUri(overMutualTls: Boolean, path: String): String =
    val origin =
      if overMutualTls then mutualTls.map(_.externalUrl).getOrElse(jwt.issuer)
      else jwt.issuer
    s"${origin.stripSuffix("/")}$path"

object CoreConfig:
  case class BootstrapConfig(
      login: String,
      password: String,
      adminUserId: java.util.UUID,
  )

  case class SmtpConfig(
      host: String,
      port: Int,
      username: String,
      password: String,
      from: Email,
      subject: String,
      startTls: Boolean,
  )

  /** How this `auth` and `central` know each other (versolauth/versola#1050): each has a key pair of its own, and
    * neither holds a secret the other could use to pass for it.
    *
    * @param keyId the `kid` of this auth's key, in the header of the tokens it signs and of the handoffs it seals; it is
    *   the `kid` of the public JWK central (and the cells) know it by
    * @param privateKey this auth's RSA key, as base64 PKCS#8 like an edge's `private-key`. It signs for central and for
    *   a cell, decrypts what central and the cells encrypt to it, and keys the stored configuration snapshot
    * @param publicKeyJwk central's service key, the public RSA JWK (with a `kid`) its calls to this auth are verified with
    */
  case class CentralSyncConfig(
      url: URL,
      keyId: String,
      privateKey: RSAPrivateKey,
      publicKeyJwk: JWT.PublicKeys,
  ):
    /** The key the stored configuration snapshot is authenticated with: local to this auth, derived from its own private
      * key, so that a snapshot another auth wrote is not one this one would serve. */
    def snapshotKey: Array[Byte] =
      val derived = Array.ofDim[Byte](32)
      Blake3.initKeyDerivationFunction(CentralSyncConfig.SnapshotContext).update(privateKey.getEncoded).doFinalize(derived)
      derived

  object CentralSyncConfig:
    /** Never to change: it separates the snapshot key from every other use of the private key. */
    private val SnapshotContext = "versola auth config snapshot v1".getBytes(StandardCharsets.UTF_8)

    /** An RSA private key, as an edge reads its own: base64 PKCS#8, in either alphabet. */
    def decodePrivateKey(raw: String): Either[String, RSAPrivateKey] =
      SecretConfig.rsaPrivateKey(raw).left.map(_.getMessage).flatMap:
        case key: RSAPrivateKey => Right(key)
        case _ => Left("not an RSA private key")

    /** Central's public JWK as JSON text: an RSA key of at least 2048 bits, with a `kid` and nothing private in it. A
      * token is verified by the key its `kid` names, so a key without one verifies nothing. */
    def decodePublicKey(raw: String): Either[String, JWT.PublicKeys] =
      Try(JWK.parse(raw)).toEither.left.map(_ => "not a JWK").flatMap:
        case key: RSAKey if key.isPrivate => Left("holds private members: only the public half belongs here")
        case key: RSAKey if Option(key.getKeyID).forall(_.isEmpty) => Left("has no kid")
        case key: RSAKey if key.size < 2048 => Left("is an RSA key of fewer than 2048 bits")
        case key: RSAKey => Right(JWT.PublicKeys(JWKSet(key)))
        case _ => Left("is not an RSA key")

  case class OtpProvider(
      method: Method,
      url: URL,
      username: Option[String],
      password: Option[String],
      body: Map[String, String],
  )

  case class JwtConfig(
      issuer: String,
      privateKey: PrivateKey,
  )

  case class Security(
      accessTokensSecret: Secret.Bytes32,
      clientSecretsSecret: Secret.Bytes16,
      refreshTokensSecret: Secret.Bytes32,
      authCodesSecret: Secret.Bytes32,
      sessionsSecret: Secret.Bytes32,
      passwordsSecret: Secret.Bytes16,
      conversationCookieSecret: Secret.Bytes32,
      sessionCookieSecret: Secret.Bytes32,
      userAgentCookieSecret: Secret.Bytes32,
      parRequestsSecret: Secret.Bytes32,
      dpopNoncesSecret: Secret.Bytes32,
      /** Keys the hash of a phone number or address in the antifraud event log. Absent in a config written
        * before it existed, which then derives the key from [[sessionsSecret]]; a new deployment holds a key of
        * its own, so rotating the sessions secret does not break the match between old and new events. */
      antifraudSubjectSecret: Option[Secret.Bytes32] = None,
  )

  /** RFC 9126 pushed authorization request endpoint settings. */
  case class ParConfig(
      requestUriTtl: Duration,
      maxRequestSize: Int,
  )

  object ParConfig:
    /** RFC 9126 §2.2 suggests a short lifetime, typically between 5 and 600 seconds. */
    val default: ParConfig = ParConfig(requestUriTtl = Duration.fromSeconds(60), maxRequestSize = 8192)

  /** RFC 9449 DPoP proof validation settings.
    *
    * Two things a deployment might expect to find here are deliberately absent, both for the
    * same reason -- the value is already carried by something a request has to consult anyway:
    *
    *   - the accepted signing algorithms are `dpop_signing_alg_values_supported` in the
    *     authorization server metadata document and are read from there (see
    *     [[Dpop.Algorithm.MetadataField]]), so the set clients discover and the set a proof is
    *     held to are one value rather than two that can drift;
    *   - whether a nonce is required at all (§8) is a tenant setting
    *     ([[versola.oauth.client.OAuthConfigurationService.requireDpopNonce]]). Turning it on
    *     costs every client of that tenant an extra round trip and breaks any that does not
    *     retry on `use_dpop_nonce`, so it has to be enablable one tenant at a time rather than
    *     for every client a deployment serves at once.
    *
    * @param iatLeeway maximum allowed distance between a proof's `iat` and the time it's
    *   checked, in either direction. This is also the window a proof has to be remembered for,
    *   so widening it costs storage on the replay guard; implementations may cap it.
    * @param nonceTtl how long a server-issued `DPoP-Nonce` remains acceptable.
    */
  case class DpopConfig(
      iatLeeway: Duration,
      nonceTtl: Duration,
  )

  object DpopConfig:
    val default: DpopConfig = DpopConfig(
      iatLeeway = Duration.fromSeconds(60),
      nonceTtl = Duration.fromSeconds(300),
    )

  /** RFC 7030 EST under `/.well-known/est/`: a client registered with `estEnrollment` gets and renews its mTLS client
    * certificate there. `simpleenroll` (the first certificate, for a one-time token) is served on the main listener and
    * `simplereenroll` (for the certificate the client has, so a new key each time) only on the mutual-TLS listener, which
    * therefore has to be configured. Auth holds no CA: it checks the request and has central issue.
    *
    * @param enabled the switch; absent or false, every EST route answers 404.
    * @param caCertificates PEM path to the certificates `cacerts` hands out, the CA a client must trust for what is issued
    *   to it. Required when `enabled`, and not defaulted to [[MutualTlsConfig.trustedCertificates]]: those are every anchor a
    *   client chain is validated against and may hold the CA of edges, which a bank must not be told to trust.
    */
  case class EstConfig(
      enabled: Boolean = false,
      caCertificates: Option[String] = None,
  )

  /** RFC 8705 §5: the listener `auth` terminates mutual TLS on itself, separate from the one
    * serving everything else.
    *
    * Separate because client authentication is negotiated in the TLS handshake, before any
    * path is known -- a certificate cannot be demanded for `/token` alone. Demanded on the
    * main listener it would be demanded of the browser at `/authorize` too, which is the
    * prompt §5 exists to avoid. So the endpoints a certificate is relevant to are served a
    * second time here, and advertised as `mtls_endpoint_aliases`.
    *
    * Absent leaves the deployment as it was before this existed: no such listener, and a
    * certificate reaches `auth` only as the header a tenant's proxy is configured to forward
    * (§6.5).
    *
    * @param certificate PEM path to the certificate this listener presents.
    * @param privateKey PEM path to its key, unencrypted PKCS#8 -- the only form the TLS stack
    *                   here reads back.
    * @param trustedCertificates PEM path to the anchors a client's chain is validated against.
    *   Required rather than optional, and deliberately so: left unset, Netty falls back to the
    *   JDK's default trust store, and every publicly-trusted CA on earth would then vouch for
    *   clients of this endpoint. There is no safe default to fall back to, so there is none.
    * @param externalUrl the address a client is told to reach this listener at -- `mtls_endpoint_aliases`
    *   (RFC 8705 §5) names it, not [[certificate]]/[[privateKey]]/[[trustedCertificates]],
    *   because a deployment behind a NAT or a TCP-passthrough load balancer binds this
    *   listener on one address and publishes another, the same relationship [[JwtConfig.issuer]]
    *   already has to `PORT`.
    * @param edgeTrustedCertificates PEM path to the certificate(s) that issue the certificates of
    *   edges (`urn:versola:edge:<id>`). A call to `/userinfo` over this listener with a certificate one
    *   of them signed is taken as an edge's, which has verified the holder of the token itself
    *   (`EdgeCertificateTrust`). Must be a dedicated issuer, never one that also signs clients'
    *   certificates, and these certificates must also be among [[trustedCertificates]] so that the
    *   handshake accepts them. Absent: no certificate is an edge's.
    */
  case class MutualTlsConfig(
      certificate: String,
      privateKey: String,
      trustedCertificates: String,
      externalUrl: String,
      edgeTrustedCertificates: Option[String] = None,
  )
