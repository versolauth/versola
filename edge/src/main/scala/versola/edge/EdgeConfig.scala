package versola.edge

import versola.util.{EnvName, Secret}
import zio.Duration

/**
 * Configuration for the Edge service.
 * 
 * Unlike CoreConfig used by the auth service, EdgeConfig does not include JWT configuration
 * since the edge service doesn't issue JWTs - it only stores and forwards tokens.
 */
case class EdgeConfig(
    runtime: EdgeConfig.Runtime,
    telemetry: Option[EdgeConfig.Telemetry],
    security: EdgeConfig.Security,
)

object EdgeConfig:
  /**
   * Runtime configuration (environment name)
   * Reuses the same structure as CoreConfig.Runtime
   */
  case class Runtime(
      env: EnvName,
  )

  /**
   * Telemetry configuration (optional)
   * Reuses the same structure as CoreConfig.Telemetry
   */
  case class Telemetry(
      collector: String,
  )

  /**
   * Security configuration for the edge service.
   * 
   * Edge only needs encryption keys for storing tokens securely.
   * Unlike auth service, edge doesn't need peppers for MAC operations
   * since it doesn't generate or validate tokens - it only stores them.
   */
  case class Security(
      tokenEncryption: EdgeConfig.Security.TokenEncryption,
      edgeSessions: EdgeConfig.Security.EdgeSessions,
  )

  object Security:
    /**
     * Configuration for encrypting stored tokens (access tokens, refresh tokens)
     */
    case class TokenEncryption(
        key: Secret.Bytes32,
    )

    /**
     * Configuration for edge session management
     */
    case class EdgeSessions(
        pepper: Secret.Bytes32,
        ttl: Duration,
    )

