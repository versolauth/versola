package versola.auth

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.OAuthApp
import versola.auth.model.{AccessToken, DeviceId}
import versola.oauth.conversation.model.AuthId
import versola.security.Secret
import versola.user.model.UserId
import versola.util.{CoreConfig, EnvName}
import zio.json.ast.Json

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Date, UUID}
import zio.durationInt

object TestEnvConfig:

  // Generate test RSA key pair for JWT
  private val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
  keyPairGenerator.initialize(2048)
  private val keyPair = keyPairGenerator.generateKeyPair()
  val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  val publicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]

  private val testKeyId = "test-key-id"

  // Create RSA JWK for proper Base64URL encoding
  private val rsaJWK = new RSAKey.Builder(publicKey)
    .keyID(testKeyId)
    .algorithm(JWSAlgorithm.RS256)
    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
    .build()

  val jwksJson =
    Json.Obj(
      "keys" -> Json.Arr(
        Json.Obj(
          "kid" -> Json.Str(testKeyId),
          "kty" -> Json.Str("RSA"),
          "use" -> Json.Str("sig"),
          "alg" -> Json.Str("RS256"),
          "n" -> Json.Str(rsaJWK.getModulus.toString),
          "e" -> Json.Str(rsaJWK.getPublicExponent.toString),
        ),
      ),
    )

  val jwtConfig = CoreConfig.JwtConfig(
    privateKey = privateKey,
    publicKey = jwksJson,
    issuer = "https://versolauth.com",
  )


  val coreConfig = CoreConfig(
    runtime = CoreConfig.Runtime(EnvName.Test("test")),
    telemetry = None,
    security = CoreConfig.Security(
      clientSecrets = CoreConfig.Security.ClientSecrets(
        Secret.Bytes16(Array.fill(16)(0.toByte)),
      ),
      refreshTokens = CoreConfig.Security.RefreshTokens(
        Secret.Bytes32(Array.fill(32)(0.toByte)),
      ),
      authConversation = CoreConfig.Security.AuthConversation(
        ttl = 15.minutes,
      ),
      authCodes = CoreConfig.Security.AuthorizationCodes(
        Secret.Bytes32(Array.fill(32)(0.toByte)),
      ),
    ),
    jwt = jwtConfig,
  )

  def buildCoreConfig(envName: EnvName): CoreConfig =
    coreConfig.copy(
      runtime = coreConfig.runtime.copy(env = envName),
    )

  // Create a valid test access token following the same rules as TokenService
  def createTestAccessToken(
      userId: UserId = UserId(UUID.randomUUID()),
      authId: AuthId = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")),
      deviceId: DeviceId = DeviceId(UUID.fromString("11111111-2222-3333-4444-555555555555")),
  ): AccessToken = {
    val now = Instant.now()
    val claims = new JWTClaimsSet.Builder()
      .issuer("app.dvor")
      .subject(userId.toString)
      .audience("app.dvor")
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(12 * 3600))) // 12 hours like TokenService.AccessToken
      .jwtID(UUID.randomUUID().toString.replace("-", ""))
      .claim("auth_id", authId)
      .claim("device_id", deviceId)
      .build()

    val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
      .`type`(JOSEObjectType("at+jwt")) // Same type as TokenService.AccessToken
      .keyID(testKeyId)
      .build()

    val jwt = new SignedJWT(header, claims)
    val signer = new RSASSASigner(privateKey)
    jwt.sign(signer)

    AccessToken(jwt.serialize())
  }
