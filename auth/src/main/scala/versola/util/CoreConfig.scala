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
    * The accepted signing algorithms are deliberately absent: they are
    * `dpop_signing_alg_values_supported` in the authorization server metadata document and are
    * read from there (see [[Dpop.Algorithm.MetadataField]]), so the set clients discover and
    * the set a proof is held to are one value rather than two that can drift.
    *
    * @param iatLeeway maximum allowed distance between a proof's `iat` and the time it's
    *   checked, in either direction. This is also the window a proof has to be remembered for,
    *   so widening it costs storage on the replay guard; implementations may cap it.
    * @param nonceTtl how long a server-issued `DPoP-Nonce` remains acceptable.
    * @param requireNonce RFC 9449 §8/§9: when true, every proof this server checks must carry
    *   a nonce it issued, and a request without one is answered with `use_dpop_nonce` and a
    *   fresh nonce to retry with. Costs each client one extra round trip per endpoint, which is
    *   why it is a deployment choice rather than the default -- unlike at edge, where a proxied
    *   call is the only thing a captured proof could be replayed against and a nonce is
    *   unconditional.
    */
  case class DpopConfig(
      iatLeeway: Duration,
      nonceTtl: Duration,
      requireNonce: Boolean,
  )

  object DpopConfig:
    val default: DpopConfig = DpopConfig(
      iatLeeway = Duration.fromSeconds(60),
      nonceTtl = Duration.fromSeconds(300),
      requireNonce = false,
    )