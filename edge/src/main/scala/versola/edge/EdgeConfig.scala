package versola.edge

import versola.edge.model.EdgeId
import versola.util.{EnvName, JWT, RsaKeyPair, Secret}
import zio.Duration
import zio.http.URL

import java.security.PrivateKey
import javax.crypto.SecretKey

case class EdgeConfig(
    id: EdgeId,
    keyId: String,
    privateKey: PrivateKey,
    security: EdgeConfig.Security,
    central: EdgeConfig.CentralConfig,
    versolaUrl: URL,
)

object EdgeConfig:

  case class Security(
      tokenEncryption: EdgeConfig.Security.TokenEncryption,
      edgeSessions: EdgeConfig.Security.EdgeSessions,
  )

  object Security:
    case class TokenEncryption(
        key: Secret.Bytes32,
    )

    case class EdgeSessions(
        pepper: Secret.Bytes32,
        ttl: Duration,
    )

  case class CentralConfig(
      url: URL,
      secretKey: SecretKey,
  )

