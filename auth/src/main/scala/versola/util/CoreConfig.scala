package versola.util

import com.nimbusds.jose.jwk.JWKSet
import zio.Duration
import zio.json.*

import java.security.PrivateKey

case class CoreConfig(
    runtime: CoreConfig.Runtime,
    telemetry: Option[CoreConfig.Telemetry],
    security: CoreConfig.Security,
    jwt: CoreConfig.JwtConfig,
)

object CoreConfig:
  case class Telemetry(
      collector: String,
  )
  case class Runtime(
      env: EnvName,
  )

  case class JwtConfig(
      issuer: String,
      privateKey: PrivateKey,
      publicKey: zio.json.ast.Json.Obj,
  ):
    val jwkSet = JWKSet.parse(publicKey.toJson)

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
