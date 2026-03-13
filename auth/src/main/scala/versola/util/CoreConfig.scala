package versola.util

import versola.util.postgres.PostgresConfig
import zio.Duration

import java.security.PrivateKey

case class CoreConfig(
    security: CoreConfig.Security,
    jwt: CoreConfig.JwtConfig,
    postgres: PostgresConfig,
)

object CoreConfig:

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
