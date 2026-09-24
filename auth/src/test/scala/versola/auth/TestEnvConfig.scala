package versola.auth

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.model.DeviceId
import versola.oauth.conversation.model.AuthId
import versola.oauth.jwks.JwksService
import versola.oauth.client.model.{AuthMethod, ClientId, MtlsCertificateEncoding, MtlsCertificateSource, MutualTlsAuth, MutualTlsSubjectType, OAuthClientRecord, ScopeToken, TenantId}
import versola.oauth.mtls.ClientCertificate
import versola.oauth.model.AccessToken
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, EnvName, JWT, JsonWebKeySet, Secret}
import zio.json.*
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.{Task, UIO, ZIO}

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Date, UUID}
import javax.crypto.spec.SecretKeySpec
import zio.durationInt
import zio.http.{Method, URL}

object TestEnvConfig:

  /** A real certificate, so that a spec exercising the decoding and parsing between a proxy's
    * header and a `ClientCertificate` runs it against something a proxy could actually send.
    * Self-signed, 100-year validity: the chain was validated by the proxy, so nothing looks at
    * the issuer or the dates. Its base64 contains a `+`, which is what catches a percent-decoder
    * that also maps `+` to a space. Carries one subject alternative name of each type RFC 8705
    * §2.1.2 registers a client by. */
  val clientCertificatePem =
    """-----BEGIN CERTIFICATE-----
MIICMjCCAdegAwIBAgIUfkM9PmBrcREaTH0sxNapdUqpkKcwCgYIKoZIzj0EAwIw
PjEYMBYGA1UEAwwPcGF5bWVudHMtY2xpZW50MRUwEwYDVQQKDAxWZXJzb2xhIFRl
c3QxCzAJBgNVBAYTAktaMCAXDTI2MDkxNTExNTg1MFoYDzIxMjYwODIyMTE1ODUw
WjA+MRgwFgYDVQQDDA9wYXltZW50cy1jbGllbnQxFTATBgNVBAoMDFZlcnNvbGEg
VGVzdDELMAkGA1UEBhMCS1owWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATbI/Rt
6I2vwJq1JB4YMjY8jp1vCC0iJZ1j6jjR3ITtcVM5VjRcnWSWpzOSsXjP/ShQvBas
xgbklwIadwl8Gqs0o4GwMIGtMB0GA1UdDgQWBBTm6vs1P/3Mt16Ehjfitznu96o8
9DAfBgNVHSMEGDAWgBTm6vs1P/3Mt16Ehjfitznu96o89DAPBgNVHRMBAf8EBTAD
AQH/MFoGA1UdEQRTMFGCEmNsaWVudC5leGFtcGxlLmNvbYYdaHR0cHM6Ly9jbGll
bnQuZXhhbXBsZS5jb20vaWSHBMsAcQeBFm9wc0BjbGllbnQuZXhhbXBsZS5jb20w
CgYIKoZIzj0EAwIDSQAwRgIhAMb1OgD8yTpD6MVOMOQtcnm9ButVBD20KYPOCGQo
L/5QAiEAn9SciXW0wsr6ctErHUWF7J5ieBlZadVpUBW4bV8uyxY=
-----END CERTIFICATE-----
"""

  /** As nginx forwards it: the PEM with its newlines percent-escaped. Everything else,
    * including the `+`, is left as it stands. */
  val escapedClientCertificatePem = clientCertificatePem.replace("\n", "%0A")

  /** As Traefik forwards it: the DER bytes, base64, delimiters and newlines gone. */
  val base64DerClientCertificate =
    clientCertificatePem
      .linesIterator
      .filterNot(_.startsWith("-----"))
      .mkString

  val clientCertificateThumbprint = "XpZ7n_MhXGgX-fRZVMB1ySDA5eM-tiF4Hdogb3a9ZMo"

  val nginxCertificateSource =
    MtlsCertificateSource("ssl-client-cert", MtlsCertificateEncoding.urlEncodedPem)

  val traefikCertificateSource =
    MtlsCertificateSource("X-Forwarded-Tls-Client-Cert", MtlsCertificateEncoding.base64Der)

  val clientCertificateSubjectDn = "C=KZ,O=Versola Test,CN=payments-client"

  /** The `san_dns` name the certificate carries, which is what a client registers to
    * authenticate with it. */
  val clientCertificateDnsName = "client.example.com"

  /** The same certificate as a parsed value, for a spec that needs a `ClientCertificate`
    * rather than the header a proxy forwards it in. Parsed rather than hand-built so that it
    * cannot drift from the certificate it stands for. */
  val clientCertificate: ClientCertificate =
    ClientCertificate.parse(clientCertificatePem, MtlsCertificateEncoding.urlEncodedPem)
      .getOrElse(throw IllegalStateException("the test client certificate does not parse"))

  /** A client registered to authenticate with [[clientCertificate]]. RFC 8705 §2.1 makes the
    * certificate the credential, so it holds no secret. */
  def mtlsClient(id: ClientId): OAuthClientRecord = OAuthClientRecord(
    id = id,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Mutual TLS Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read")),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, clientCertificateDnsName)),
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
  )

  /** [[clientCertificate]]'s own public key as an RFC 7517 key set, which is what RFC 8705
    * §2.2 matches a self-signed certificate against. Derived from the certificate rather than
    * written out beside it, so the two cannot drift. */
  val clientCertificateKeySet: JsonWebKeySet =
    val publicKey = java.security.KeyFactory.getInstance("EC")
      .generatePublic(java.security.spec.X509EncodedKeySpec(clientCertificate.subjectPublicKeyInfo))
      .asInstanceOf[java.security.interfaces.ECPublicKey]
    val jwk = ECKey.Builder(Curve.P_256, publicKey).keyID("cert-ec-1").build()
    JsonWebKeySet(
      Json.Obj("keys" -> Json.Arr(jwk.toJSONString.fromJson[Json.Obj].toOption.get)),
    )

  /** Some other client's certificate, for checking that a registered subject is matched rather
    * than merely that a certificate arrived. Its key differs too: §2.2 compares the key and
    * nothing else, so a stand-in that kept this one's would authenticate as it. */
  val otherClientCertificate: ClientCertificate = clientCertificate.copy(
    thumbprint = "b0HRnkRZIOoyEKTJQTrIdUMKrPwSZSe9Ei-MbvVLt1E",
    subjectDn = "C=KZ,O=Versola Test,CN=other-client",
    subjectAlternativeNames = Map(MutualTlsSubjectType.san_dns -> Set("other.example.com")),
    subjectPublicKeyInfo = otherPublicKeyInfo,
  )

  private def otherPublicKeyInfo: Array[Byte] =
    val generator = java.security.KeyPairGenerator.getInstance("EC")
    generator.initialize(Curve.P_256.toECParameterSpec)
    generator.generateKeyPair().getPublic.getEncoded

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

  val signingKey: JWT.Signature.Asymmetric =
    JWT.Signature.Asymmetric(JWT.Algorithm.RS256, publicKeys.active.id, privateKey)

  val jwksService: JwksService = new JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(publicKeys)
    override def signingKey(tenantId: TenantId): Task[JWT.Signature.Asymmetric] =
      ZIO.succeed(TestEnvConfig.signingKey)
    override def refresh: Task[Unit] = ZIO.unit


  val coreConfig = CoreConfig(
    security = CoreConfig.Security(
      accessTokensSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      clientSecretsSecret = Secret.Bytes16(Array.fill(16)(0.toByte)),
      refreshTokensSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      authCodesSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      sessionsSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      passwordsSecret = Secret.Bytes16(Array.fill(16)(0.toByte)),
      conversationCookieSecret = Secret.Bytes32(Array.fill(32)(0.toByte)),
      sessionCookieSecret      = Secret.Bytes32(Array.fill(32)(0.toByte)),
      userAgentCookieSecret    = Secret.Bytes32(Array.fill(32)(0.toByte)),
      parRequestsSecret        = Secret.Bytes32(Array.fill(32)(0.toByte)),
      dpopNoncesSecret         = Secret.Bytes32(Array.fill(32)(0.toByte)),
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
    configurationCacheRefreshInterval = 5.minutes,
    par = None,
    dpop = None,
    argon2 = None,
  )