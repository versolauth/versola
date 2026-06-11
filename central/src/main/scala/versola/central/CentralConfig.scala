package versola.central

import versola.util.Secret
import zio.Duration
import zio.http.URL

import javax.crypto.SecretKey

case class CentralConfig(
    initialize: Boolean,
    clientSecretsPepper: Secret,
    secretKey: SecretKey,
    auth: CentralConfig.AuthConfig,
    userOutbox: CentralConfig.UserOutboxConfig,
)

object CentralConfig:
  case class AuthConfig(url: URL)

  case class UserOutboxConfig(
      pollInterval: Duration,
      batchSize: Int,
      lease: Duration,
      maxBackoff: Duration,
      maxAttempts: Int,
  )
