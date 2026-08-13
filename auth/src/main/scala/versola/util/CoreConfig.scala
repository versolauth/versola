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
    userRegistrationOutbox: Option[CoreConfig.UserRegistrationOutboxConfig],
):
  def parOrDefault: CoreConfig.ParConfig = par.getOrElse(CoreConfig.ParConfig.default)

  def userRegistrationOutboxOrDefault: CoreConfig.UserRegistrationOutboxConfig =
    userRegistrationOutbox.getOrElse(CoreConfig.UserRegistrationOutboxConfig.default)

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
)

  /** RFC 9126 pushed authorization request endpoint settings. */
  case class ParConfig(
      requestUriTtl: Duration,
      maxRequestSize: Int,
  )

  object ParConfig:
    /** RFC 9126 §2.2 suggests a short lifetime, typically between 5 and 600 seconds. */
    val default: ParConfig = ParConfig(requestUriTtl = Duration.fromSeconds(60), maxRequestSize = 8192)

  /** Delivery of self-service registrations to central. Mirrors central's own outbox settings. */
  case class UserRegistrationOutboxConfig(
      pollInterval: Duration,
      batchSize: Int,
      lease: Duration,
      maxBackoff: Duration,
      maxAttempts: Int,
  )

  object UserRegistrationOutboxConfig:
    val default: UserRegistrationOutboxConfig = UserRegistrationOutboxConfig(
      pollInterval = Duration.fromSeconds(5),
      batchSize = 100,
      lease = Duration.fromSeconds(30),
      maxBackoff = Duration.fromSeconds(300),
      maxAttempts = 5,
    )