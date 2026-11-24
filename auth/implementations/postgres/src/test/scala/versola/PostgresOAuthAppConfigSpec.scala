package versola

import versola.util.{CoreConfig, Secret, SecretConfigChecks}
import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*

import java.security.KeyPairGenerator
import java.util.Base64

/** The decoders PostgresOAuthApp installs for its secrets: a wrong or malformed value must not reach the config error,
  * while `=` padding and the length check keep working.
  */
object PostgresOAuthAppConfigSpec extends ZIOSpecDefault:

  import PostgresOAuthApp.given

  private val rsa = KeyPairGenerator.getInstance("RSA")
  rsa.initialize(2048)
  private val pair = rsa.generateKeyPair()
  private val privateKey = Base64.getEncoder.encodeToString(pair.getPrivate.getEncoded)

  private def publicJwk(extra: String = ""","kid":"central-1"""") =
    val key = pair.getPublic.asInstanceOf[java.security.interfaces.RSAPublicKey]
    val encoder = Base64.getUrlEncoder.withoutPadding
    s"""{"kty":"RSA","n":"${encoder.encodeToString(key.getModulus.toByteArray.dropWhile(_ == 0))}","e":"${encoder.encodeToString(
        key.getPublicExponent.toByteArray,
      )}"$extra}"""

  private def central(entries: Map[String, String]) =
    ConfigProvider.fromMap(entries.map((key, value) => s"central.$key" -> value))
      .kebabCase
      .nested("central")
      .load(deriveConfig[CoreConfig.CentralSyncConfig])
      .either

  private val complete = Map("url" -> "http://central:9201", "key-id" -> "auth-1", "private-key" -> privateKey, "public-key-jwk" -> publicJwk())

  def spec = suite("PostgresOAuthApp configuration")(
    SecretConfigChecks.decoders(
      bytes16 = Some(deriveConfig[Secret.Bytes16]),
      bytes32 = deriveConfig[Secret.Bytes32],
      plain = deriveConfig[Secret],
      aesKey = Some(deriveConfig[javax.crypto.SecretKey]),
      privateKey = Some(deriveConfig[java.security.PrivateKey]),
    ),
    // versolauth/versola#1050
    suite("central")(
      test("reads the key id, the private key as an edge reads its own, and central's public JWK") {
        for
          config <- central(complete)
        yield assertTrue(
          config.exists(_.keyId == "auth-1"),
          config.exists(_.privateKey.getEncoded.sameElements(pair.getPrivate.getEncoded)),
          config.exists(_.publicKeyJwk.active.id == "central-1"),
        )
      },
      test("reads a private key in the URL-safe alphabet too") {
        for
          config <- central(complete.updated("private-key", privateKey.replace('+', '-').replace('/', '_')))
        yield assertTrue(config.isRight)
      },
      test("refuses a private key that is not an RSA PKCS#8 key, and names no part of it") {
        for
          notBase64 <- central(complete.updated("private-key", "not-a-key-s3cr3t-marker"))
          ecKey = Base64.getEncoder.encodeToString(KeyPairGenerator.getInstance("EC").generateKeyPair().getPrivate.getEncoded)
          ec <- central(complete.updated("private-key", ecKey))
        yield assertTrue(notBase64.isLeft, ec.isLeft, !notBase64.left.exists(_.toString.contains("s3cr3t-marker")))
      },
      test("refuses a public JWK that is not usable: no kid, a private member, not RSA, not JSON") {
        for
          noKid <- central(complete.updated("public-key-jwk", publicJwk(extra = "")))
          withPrivate <- central(complete.updated("public-key-jwk", publicJwk(extra = ""","kid":"k","d":"AQAB"""")))
          notRsa <- central(
            complete.updated(
              "public-key-jwk",
              """{"kty":"EC","crv":"P-256","kid":"k","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}""",
            ),
          )
          notJson <- central(complete.updated("public-key-jwk", "central-key"))
        yield assertTrue(noKid.isLeft, withPrivate.isLeft, notRsa.isLeft, notJson.isLeft)
      },
      test("has no secret-key any more: a configuration without the new keys is refused, whatever else it holds") {
        for
          old <- central(Map("url" -> "http://central:9201", "secret-key" -> "uEO6uav7ee73nGTFt6Voiwln4nMdcKskKdILk48Xjwc"))
        yield assertTrue(old.isLeft)
      },
    ),
  )
