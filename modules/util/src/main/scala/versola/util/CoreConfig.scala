package versola.util

import com.nimbusds.jose.jwk.JWKSet
import versola.security.Secret

import java.security.PrivateKey
import zio.json.*

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
      privateKey: PrivateKey,
      publicKey: zio.json.ast.Json.Obj,
  ):
    val jwkSet = JWKSet.parse(publicKey.toJson)

  case class Security(
      clientSecrets: CoreConfig.Security.ClientSecrets,
      refreshTokens: CoreConfig.Security.RefreshTokens
  )

  object Security:
    case class ClientSecrets(
        pepper: Secret.Bytes16,
    )
    
    case class RefreshTokens(
        pepper: Secret.Bytes32,
    )
