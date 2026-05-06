package versola.util

import zio.Duration
import zio.http.URL

import java.security.PrivateKey
import javax.crypto.SecretKey

case class CoreConfig(
    security: CoreConfig.Security,
    jwt: CoreConfig.JwtConfig,
    central: CoreConfig.CentralSyncConfig
)

object CoreConfig:
  case class CentralSyncConfig(
      url: URL,
      secretKey: SecretKey,
  )

  case class JwtConfig(
      issuer: String,
      privateKey: PrivateKey,
      publicKeys: JWT.PublicKeys,
  )

  case class Security(
      accessTokens: CoreConfig.Security.AccessTokens,
      clientSecrets: CoreConfig.Security.ClientSecrets,
      refreshTokens: CoreConfig.Security.RefreshTokens,
      authConversation: CoreConfig.Security.AuthConversation,
      authCodes: CoreConfig.Security.AuthorizationCodes,
      sessions: CoreConfig.Security.Sessions,
      ssoSession: CoreConfig.Security.SsoSession,
      passwords: CoreConfig.Security.Passwords,
  )

  object Security:
    case class ClientSecrets(
        pepper: Secret.Bytes16,
    )

    case class RefreshTokens(
        pepper: Secret.Bytes32,
        ttl: Duration,
    )

    case class AccessTokens(
        pepper: Secret.Bytes32,
    )

    case class AuthorizationCodes(
        pepper: Secret.Bytes32,
    )

    case class Sessions(
        pepper: Secret.Bytes32,
    )

    case class AuthConversation(
        ttl: Duration,
    )

    case class SsoSession(
        ttl: Duration,
    )

    case class Passwords(
        pepper: Secret.Bytes16,
        historySize: Int, // Number of passwords to keep in history (including current)
        numDifferent: Int, // Number of different passwords required before allowing reuse
    )
