package versola.util

import zio.http.{Method, URL}
import zio.Duration

import java.security.PrivateKey
import javax.crypto.SecretKey

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
):
  def parOrDefault: CoreConfig.ParConfig = par.getOrElse(CoreConfig.ParConfig.default)

  def dpopOrDefault: CoreConfig.DpopConfig = dpop.getOrElse(CoreConfig.DpopConfig.default)

  def argon2OrDefault: Argon2Config = argon2.getOrElse(Argon2Config.default)

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
  case class CentralSyncConfig(
      url: URL,
      secretKey: SecretKey,
  )

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
    */
  case class MutualTlsConfig(
      certificate: String,
      privateKey: String,
      trustedCertificates: String,
  )