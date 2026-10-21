package versola

import versola.util.{Secret, SecretConfigChecks}
import zio.config.magnolia.deriveConfig
import zio.test.*

/** The decoders PostgresOAuthApp installs for its secrets: a wrong or malformed value must not reach the config error,
  * while `=` padding and the length check keep working.
  */
object PostgresOAuthAppConfigSpec extends ZIOSpecDefault:

  import PostgresOAuthApp.given

  def spec = SecretConfigChecks.decoders(
    bytes16 = Some(deriveConfig[Secret.Bytes16]),
    bytes32 = deriveConfig[Secret.Bytes32],
    plain = deriveConfig[Secret],
    aesKey = Some(deriveConfig[javax.crypto.SecretKey]),
    privateKey = Some(deriveConfig[java.security.PrivateKey]),
  )
