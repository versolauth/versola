package versola.util

import versola.auth.TestEnvConfig
import zio.test.*

import java.util.Base64

/** The keys an auth is configured with towards central (versolauth/versola#1050), and the key the stored configuration
  * snapshot is derived from. */
object CentralSyncConfigSpec extends ZIOSpecDefault:

  private val config = TestEnvConfig.coreConfig.central

  def spec = suite("CoreConfig.CentralSyncConfig")(
    test("the snapshot key is 32 bytes, stable for a key and different for another") {
      val another = config.copy(privateKey = TestEnvConfig.anotherCellKeyPair.privateKey)
      assertTrue(
        config.snapshotKey.length == 32,
        config.snapshotKey.sameElements(config.snapshotKey),
        !config.snapshotKey.sameElements(another.snapshotKey),
        // not the encoding itself
        !config.snapshotKey.sameElements(config.privateKey.getEncoded.take(32)),
      )
    },
    test("decodePrivateKey reads base64 PKCS#8 in either alphabet and refuses anything else") {
      val standard = Base64.getEncoder.encodeToString(config.privateKey.getEncoded)
      val urlSafe = Base64.getUrlEncoder.withoutPadding.encodeToString(config.privateKey.getEncoded)
      assertTrue(
        CoreConfig.CentralSyncConfig.decodePrivateKey(standard).map(_.getEncoded.toList) == Right(config.privateKey.getEncoded.toList),
        CoreConfig.CentralSyncConfig.decodePrivateKey(urlSafe).isRight,
        CoreConfig.CentralSyncConfig.decodePrivateKey("").isLeft,
        CoreConfig.CentralSyncConfig.decodePrivateKey("AAAA").isLeft,
      )
    },
    test("decodePublicKey accepts central's public RSA JWK and refuses a private one") {
      val publicJwk = TestEnvConfig.centralKeyPair.toPublicJwk.toString
      val withPrivate = com.nimbusds.jose.jwk.RSAKey.Builder(TestEnvConfig.centralKeyPair.publicKey)
        .privateKey(TestEnvConfig.centralKeyPair.privateKey)
        .keyID("k")
        .build()
        .toJSONString
      assertTrue(
        CoreConfig.CentralSyncConfig.decodePublicKey(publicJwk).map(_.active.id) == Right(TestEnvConfig.centralKeyPair.keyId),
        CoreConfig.CentralSyncConfig.decodePublicKey(withPrivate).isLeft,
      )
    },
  )
