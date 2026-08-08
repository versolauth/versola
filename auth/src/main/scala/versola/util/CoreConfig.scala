package versola.util

import zio.http.{Method, URL}
import zio.{IO, ZIO}

import java.security.PrivateKey
import javax.crypto.SecretKey

case class CoreConfig(
    security: CoreConfig.Security,
    jwt: CoreConfig.JwtConfig,
    central: CoreConfig.CentralSyncConfig,
    bootstrap: Option[CoreConfig.BootstrapConfig],
    otpProvider: Option[CoreConfig.OtpProvider],
    smtp: Option[CoreConfig.SmtpConfig],
)

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
      // keyId and privateKey are always the matching pair auth signs with — kept
      // together (not looked up dynamically from JwksService) so signing can never
      // label a token with a kid that doesn't correspond to the private key that
      // actually signed it. See EdgeConfig.keyId/privateKey for the same pattern.
      //
      // Optional (rather than required) on purpose: runtime config is deployed
      // separately from the image (see #104 review discussion). If jwt.key-id is
      // missing -- e.g. an old config paired with a new image -- signing fails per
      // request via requireKeyId below instead of the whole service refusing to boot.
      keyId: Option[String],
      privateKey: PrivateKey,
  ):
    def requireKeyId: IO[RuntimeException, String] =
      ZIO.fromOption(keyId).orElseFail(RuntimeException(
        "jwt.key-id is not configured -- cannot sign JWTs. Add it alongside jwt.private-key.",
      ))

  case class Security(
    accessTokensSecret: Secret.Bytes32,
    clientSecretsSecret: Secret.Bytes16,
    refreshTokensSecret: Secret.Bytes32,
    authCodesSecret: Secret.Bytes32,
    sessionsSecret: Secret.Bytes32,
    passwordsSecret: Secret.Bytes16,
    conversationCookieSecret: Secret.Bytes32,
    sessionCookieSecret: Secret.Bytes32,
)