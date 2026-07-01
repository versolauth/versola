package versola.central

import versola.util.Secret
import zio.Duration
import zio.http.URL

import javax.crypto.spec.SecretKeySpec

object TestCentralConfig:
  val authConfig = CentralConfig.AuthConfig(
    url = URL.decode("http://localhost:9001").toOption.get,
  )

  val userOutboxConfig = CentralConfig.UserOutboxConfig(
    pollInterval = Duration.fromSeconds(1),
    batchSize = 32,
    lease = Duration.fromSeconds(60),
    maxBackoff = Duration.fromSeconds(300),
    maxAttempts = 10,
  )

  val config = CentralConfig(
    bootstrap = None,
    clientSecretsSecret = Secret(Array.fill(16)(5.toByte)),
    secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES"),
    auth = authConfig,
    userOutbox = userOutboxConfig,
  )
