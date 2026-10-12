package versola

import versola.util.{Secret, SecretConfigChecks}
import zio.config.magnolia.deriveConfig
import zio.test.*

/** The decoders PostgresEdgeApp installs for its secrets: a wrong or malformed value must not reach the config error,
  * while `=` padding and the length check keep working.
  */
object PostgresEdgeAppConfigSpec extends ZIOSpecDefault:

  import PostgresEdgeApp.given

  def spec = SecretConfigChecks.decoders(
    bytes16 = Some(deriveConfig[Secret.Bytes16]),
    bytes32 = deriveConfig[Secret.Bytes32],
    plain = deriveConfig[Secret],
    aesKey = None,
    privateKey = Some(deriveConfig[java.security.PrivateKey]),
  )
