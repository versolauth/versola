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
    * @param allowedAlgorithms signing algorithms an incoming proof's `alg` may use
    *   (`dpop_signing_alg_values_supported`); the client picks based on the key it holds, so
    *   this is independent of [[JwtConfig]] / this server's own signing algorithm.
    * @param iatLeeway maximum allowed distance between a proof's `iat` and the time it's
    *   checked, in either direction. This is also the window a proof has to be remembered for,
    *   so widening it costs storage on the replay guard; implementations may cap it.
    * @param nonceTtl how long a server-issued `DPoP-Nonce` remains acceptable.
    */
  case class DpopConfig(
      allowedAlgorithms: Set[Dpop.Algorithm],
      iatLeeway: Duration,
      nonceTtl: Duration,
  )

  object DpopConfig:
    /** RFC 9449 §5 mandates `ES256`; `PS256` is included for FAPI 2.0 deployments. `RS256` is
      * supported by [[Dpop.verify]] but left out of the default allow-list -- FAPI disallows it
      * outright, and non-FAPI deployments can opt back in explicitly. */
    val default: DpopConfig = DpopConfig(
      allowedAlgorithms = Set(Dpop.Algorithm.ES256, Dpop.Algorithm.PS256),
      iatLeeway = Duration.fromSeconds(60),
      nonceTtl = Duration.fromSeconds(300),
    )