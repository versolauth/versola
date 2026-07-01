package versola.auth

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.model.DeviceId
import versola.oauth.conversation.model.AuthId
import versola.oauth.jwks.JwksService
import versola.oauth.model.AccessToken
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, EnvName, JWT, Secret}
import zio.json.ast.Json
import zio.{UIO, ZIO}

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Date, UUID}
import javax.crypto.spec.SecretKeySpec
import zio.durationInt
import zio.http.{Method, URL}

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
    issuer = "https://versolauth.com",
  )

  val publicKeys: JWT.PublicKeys = JWT.PublicKeys.fromJson(jwksJson)

  val jwksService: JwksService = new JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(publicKeys)


  val coreConfig = CoreConfig(
    security = CoreConfig.Security(
      accessTokensSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      clientSecretsSecret = Secret.Bytes16(Array.fill(16)(0.toByte)),
      refreshTokensSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      authCodesSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      sessionsSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      passwordsSecret = Secret.Bytes16(Array.fill(16)(0.toByte)),
    ),
    jwt = jwtConfig,
    central = CoreConfig.CentralSyncConfig(
      url = URL.empty,
      secretKey = SecretKeySpec(Array.fill(32)(0.toByte), "AES"),
    ),
    bootstrap = None,
    otpProvider = Some(
      CoreConfig.OtpProvider(
        method = Method.POST,
        url = URL.empty,
        username = None,
        password = None,
        body = Map.empty,
      )
    ),
    smtp = Some(
      CoreConfig.SmtpConfig(
        host = "localhost",
        port = 25,
        username = "user",
        password = "password",
        from = Email("test@versola.com"),
        subject = "Test OTP",
        startTls = true,
      )
    ),
  )