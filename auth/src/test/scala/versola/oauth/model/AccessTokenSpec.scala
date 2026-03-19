package versola.oauth.model

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.TestEnvConfig
import versola.oauth.client.model.{Claim, ClientId, ScopeToken}
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims}
import versola.user.model.UserId
import versola.util.{JWT, UnitSpecBase}
import zio.json.*
import zio.test.*
import zio.{Clock, ZIO}

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Date, UUID}

object AccessTokenSpec extends UnitSpecBase:

  // Test RSA key pair
  private val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
  keyPairGenerator.initialize(2048)
  private val keyPair = keyPairGenerator.generateKeyPair()
  private val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val publicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]
  private val testKeyId = "test-key-1"

  // Create JWK set for signature verification
  private val rsaJWK = new RSAKey.Builder(publicKey)
    .keyID(testKeyId)
    .algorithm(JWSAlgorithm.RS256)
    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
    .build()

  private val jwkSet = new JWKSet(rsaJWK)
  private val publicKeysForVerification = JWT.PublicKeys(jwkSet)

  // Test data
  private val testUserId = UserId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
  private val testClientId = ClientId("test-client-123")
  private val testScopes = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email"))
  private val testRequestedClaims = RequestedClaims(
    userinfo = Map(
      Claim("email") -> ClaimRequest(None, None, None),
      Claim("name") -> ClaimRequest(Some(true), None, None),
    ),
    idToken = Map.empty,
  )
  private val testUiLocales = Vector("en-US", "fr-CA")

  /**
   * Helper to create a signed JWT with custom claims
   */
  private def createSignedJWT(
      userId: UserId = testUserId,
      clientId: ClientId = testClientId,
      scopes: Set[ScopeToken] = testScopes,
      requestedClaims: Option[RequestedClaims] = Some(testRequestedClaims),
      uiLocales: Option[Vector[String]] = Some(testUiLocales),
      expiresAt: Option[Instant] = None,
      issuedAt: Option[Instant] = None,
      notBefore: Option[Instant] = None,
      audience: Option[String] = Some("test-client-123"),
      issuer: Option[String] = Some("https://auth.example.com"),
      jwtId: Option[String] = Some("test-jwt-id"),
      keyId: String = testKeyId,
  ): String =
    val now = Instant.now()
    val claimsBuilder = new JWTClaimsSet.Builder()
      .subject(userId.toString)
      .claim("client_id", clientId.toString)
      .claim("scope", scopes.map(_.toString).mkString(" "))

    // Add optional claims
    requestedClaims.foreach: rc =>
      // Convert to Java Map for Nimbus
      val userinfoMap = new java.util.HashMap[String, Object]()
      rc.userinfo.foreach: (key, value) =>
        val claimReqMap = new java.util.HashMap[String, Object]()
        value.essential.foreach(e => claimReqMap.put("essential", java.lang.Boolean.valueOf(e)))
        value.value.foreach(v => claimReqMap.put("value", v))
        value.values.foreach(vs => claimReqMap.put("values", java.util.Arrays.asList(vs*)))
        userinfoMap.put(key, claimReqMap)

      val idTokenMap = new java.util.HashMap[String, Object]()
      rc.idToken.foreach: (key, value) =>
        val claimReqMap = new java.util.HashMap[String, Object]()
        value.essential.foreach(e => claimReqMap.put("essential", java.lang.Boolean.valueOf(e)))
        value.value.foreach(v => claimReqMap.put("value", v))
        value.values.foreach(vs => claimReqMap.put("values", java.util.Arrays.asList(vs*)))
        idTokenMap.put(key, claimReqMap)

      val requestedClaimsMap = new java.util.HashMap[String, Object]()
      requestedClaimsMap.put("userinfo", userinfoMap)
      requestedClaimsMap.put("id_token", idTokenMap)
      claimsBuilder.claim("requested_claims", requestedClaimsMap)

    uiLocales.foreach: locales =>
      claimsBuilder.claim("ui_locales", java.util.Arrays.asList(locales*))

    expiresAt.orElse(Some(now.plusSeconds(3600))).foreach: exp =>
      claimsBuilder.expirationTime(Date.from(exp))

    issuedAt.orElse(Some(now)).foreach: iat =>
      claimsBuilder.issueTime(Date.from(iat))

    notBefore.foreach: nbf =>
      claimsBuilder.notBeforeTime(Date.from(nbf))

    audience.foreach(claimsBuilder.audience)
    issuer.foreach(claimsBuilder.issuer)
    jwtId.foreach(claimsBuilder.jwtID)

    val claims = claimsBuilder.build()

    val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
      .`type`(JOSEObjectType("at+jwt"))
      .keyID(keyId)
      .build()

    val jwt = new SignedJWT(header, claims)
    val signer = new RSASSASigner(privateKey)
    jwt.sign(signer)

    jwt.serialize()

  val spec = suite("JWTAccessToken.parseAndValidate")(
    validTokenTests,
    invalidTokenTests,
    signatureTests,
    expirationTests,
    claimsParsingTests,
  )

  def validTokenTests = suite("valid tokens")(
    test("parse and validate a valid JWT with all claims") {
      val tokenString = createSignedJWT()

      for
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification)
      yield assertTrue(
        result.subject == testUserId.toString,
        result.userId == Some(testUserId),
        result.clientId == testClientId,
        result.scope == testScopes,
        result.requestedClaims == Some(testRequestedClaims),
        result.uiLocales == Some(testUiLocales),
        result.expiresAt != null,
        result.issuedAt != null,
        result.audience == Vector(ClientId("test-client-123")),
        result.issuer == Some("https://auth.example.com"),
        result.jwtId == Some("test-jwt-id"),
      )
    },
    test("parse JWT with minimal claims (only sub and scope)") {
      val tokenString = createSignedJWT(
        requestedClaims = None,
        uiLocales = None,
        audience = None,
        issuer = None,
        jwtId = None,
      )

      for
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification)
      yield assertTrue(
        result.subject == testUserId.toString,
        result.userId == Some(testUserId),
        result.clientId == testClientId,
        result.scope == testScopes,
        result.requestedClaims.isEmpty,
        result.uiLocales.isEmpty,
        result.audience.isEmpty,
        result.issuer.isEmpty,
        result.jwtId.isEmpty,
      )
    },
  )

  def invalidTokenTests = suite("invalid tokens")(
    test("fail with NotJWT for non-JWT string") {
      for
        result <- JWT.deserialize[AccessToken]("not-a-jwt", publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.NotJWT))
    },
    test("fail with NotJWT for empty string") {
      for
        result <- JWT.deserialize[AccessToken]("", publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.NotJWT))
    },
    test("fail with NotJWT for malformed JWT") {
      for
        result <- JWT.deserialize[AccessToken]("header.payload", publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.NotJWT))
    },
  )

  def signatureTests = suite("signature verification")(
    test("fail with NotJWT for unsigned JWT (PlainJWT)") {
      import com.nimbusds.jwt.PlainJWT

      val claims = new JWTClaimsSet.Builder()
        .subject(testUserId.toString)
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")
        .build()

      val plainJWT = new PlainJWT(claims)

      for
        result <- JWT.deserialize[AccessToken](plainJWT.serialize(), publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.NotJWT))
    },
    test("fail with InvalidSignature for JWT signed with different key") {
      // Generate a different key pair
      val differentKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
      differentKeyPairGenerator.initialize(2048)
      val differentKeyPair = differentKeyPairGenerator.generateKeyPair()
      val differentPrivateKey = differentKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]

      val claims = new JWTClaimsSet.Builder()
        .subject(testUserId.toString)
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")
        .build()

      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .`type`(JOSEObjectType("at+jwt"))
        .keyID(testKeyId) // Same key ID but different key
        .build()

      val jwt = new SignedJWT(header, claims)
      val differentSigner = new RSASSASigner(differentPrivateKey)
      jwt.sign(differentSigner)

      for
        result <- JWT.deserialize[AccessToken](jwt.serialize(), publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.InvalidSignature))
    },
    test("fail with InvalidSignature for wrong key ID") {
      val tokenString = createSignedJWT(keyId = "wrong-key-id")

      for
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.InvalidSignature))
    },
    test("fail with InvalidSignature for tampered payload") {
      val tokenString = createSignedJWT()
      // Tamper with the payload by replacing a character
      val parts = tokenString.split("\\.")
      val tamperedToken = parts(0) + "." + parts(1).replace('A', 'B') + "." + parts(2)

      for
        result <- JWT.deserialize[AccessToken](tamperedToken, publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.InvalidSignature))
    },
  )

  def expirationTests = suite("expiration checks")(
    test("fail with Expired for expired token") {
      for
        now <- Clock.instant
        expiredToken = createSignedJWT(expiresAt = Some(now.minusSeconds(3600)))
        result <- JWT.deserialize[AccessToken](expiredToken, publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.Expired))
    },
    test("succeed for token expiring in the future") {
      for
        now <- Clock.instant
        validToken = createSignedJWT(expiresAt = Some(now.plusSeconds(3600)))
        result <- JWT.deserialize[AccessToken](validToken, publicKeysForVerification)
      yield assertTrue(result.expiresAt.isAfter(now))
    },
    test("fail with InvalidClaims for token without expiration") {
      val claimsBuilder = new JWTClaimsSet.Builder()
        .subject(testUserId.toString)
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")
      // Don't set expiration - this is now required

      val claims = claimsBuilder.build()

      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .`type`(JOSEObjectType("at+jwt"))
        .keyID(testKeyId)
        .build()

      val jwt = new SignedJWT(header, claims)
      val signer = new RSASSASigner(privateKey)
      jwt.sign(signer)

      for
        result <- JWT.deserialize[AccessToken](jwt.serialize(), publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.InvalidClaims))
    },
  )

  def claimsParsingTests = suite("claims parsing")(
    test("parse scope as space-separated string") {
      val tokenString = createSignedJWT(scopes =
        Set(
          ScopeToken("openid"),
          ScopeToken("profile"),
          ScopeToken("email"),
          ScopeToken("phone"),
        ),
      )

      for
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification)
      yield assertTrue(
        result.scope.size == 4,
        result.scope.contains(ScopeToken("openid")),
        result.scope.contains(ScopeToken("profile")),
        result.scope.contains(ScopeToken("email")),
        result.scope.contains(ScopeToken("phone")),
      )
    },
    test("parse requested_claims as JSON object") {
      val requestedClaims = RequestedClaims(
        userinfo = Map(
          Claim("email") -> ClaimRequest(Some(true), None, None),
          Claim("name") -> ClaimRequest(None, Some("John Doe"), None),
          Claim("locale") -> ClaimRequest(None, None, Some(Vector("en-US", "fr-CA"))),
        ),
        idToken = Map(
          "auth_time" -> ClaimRequest(Some(true), None, None),
        ),
      )
      val tokenString = createSignedJWT(requestedClaims = Some(requestedClaims))

      for
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification)
      yield assertTrue(
        result.requestedClaims.isDefined,
        result.requestedClaims.get.userinfo.size == 3,
        result.requestedClaims.get.idToken.size == 1,
      )
    },
    test("parse ui_locales as JSON array") {
      val locales = Vector("en-US", "fr-CA", "fr", "en")
      val tokenString = createSignedJWT(uiLocales = Some(locales))

      for
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification)
      yield assertTrue(
        result.uiLocales == Some(locales),
      )
    },
    test("parse standard JWT claims (iat, nbf, aud, iss, jti)") {
      for
        now <- Clock.instant
        tokenString = createSignedJWT(
          issuedAt = Some(now),
          notBefore = Some(now.minusSeconds(60)),
          audience = Some("https://api.example.com"),
          issuer = Some("https://auth.example.com"),
          jwtId = Some("unique-jwt-id-123"),
        )
        result <- JWT.deserialize[AccessToken](tokenString, publicKeysForVerification)
      yield assertTrue(
        result.issuedAt != null,
        result.notBefore.isDefined,
        result.audience == Vector(ClientId("https://api.example.com")),
        result.issuer == Some("https://auth.example.com"),
        result.jwtId == Some("unique-jwt-id-123"),
      )
    },
    test("parse audience as single string") {
      // Create JWT with audience as a single string (not array)
      val now = Instant.now()
      val claimsBuilder = new JWTClaimsSet.Builder()
        .subject(testUserId.toString)
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")
        .audience("client-456")
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .issueTime(Date.from(now))

      val claims = claimsBuilder.build()

      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .`type`(JOSEObjectType("at+jwt"))
        .keyID(testKeyId)
        .build()

      val jwt = new SignedJWT(header, claims)
      val signer = new RSASSASigner(privateKey)
      jwt.sign(signer)

      for
        result <- JWT.deserialize[AccessToken](jwt.serialize(), publicKeysForVerification)
      yield assertTrue(
        result.audience == Vector(ClientId("client-456")),
      )
    },
    test("parse audience as array of strings") {
      // Create JWT with audience as an array
      val now = Instant.now()
      val claimsBuilder = new JWTClaimsSet.Builder()
        .subject(testUserId.toString)
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")
        .audience(java.util.Arrays.asList("client-1", "client-2", "client-3"))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .issueTime(Date.from(now))

      val claims = claimsBuilder.build()

      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .`type`(JOSEObjectType("at+jwt"))
        .keyID(testKeyId)
        .build()

      val jwt = new SignedJWT(header, claims)
      val signer = new RSASSASigner(privateKey)
      jwt.sign(signer)

      for
        result <- JWT.deserialize[AccessToken](jwt.serialize(), publicKeysForVerification)
      yield assertTrue(
        result.audience == Vector(ClientId("client-1"), ClientId("client-2"), ClientId("client-3")),
      )
    },
    test("fail with InvalidClaims for missing sub claim") {
      val claimsBuilder = new JWTClaimsSet.Builder()
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")
      // Don't set subject

      val claims = claimsBuilder.build()

      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .`type`(JOSEObjectType("at+jwt"))
        .keyID(testKeyId)
        .build()

      val jwt = new SignedJWT(header, claims)
      val signer = new RSASSASigner(privateKey)
      jwt.sign(signer)

      for
        result <- JWT.deserialize[AccessToken](jwt.serialize(), publicKeysForVerification).either
      yield assertTrue(result == Left(JWT.Error.InvalidClaims))
    },
    test("parse JWT with non-UUID subject (client_credentials token)") {
      val claimsBuilder = new JWTClaimsSet.Builder()
        .subject("test-client-123") // client_id as subject
        .claim("client_id", testClientId.toString)
        .claim("scope", "openid")

      val claims = claimsBuilder.build()

      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .`type`(JOSEObjectType("at+jwt"))
        .keyID(testKeyId)
        .build()

      val jwt = new SignedJWT(header, claims)
      val signer = new RSASSASigner(privateKey)
      jwt.sign(signer)

      for
        result <- JWT.deserialize[AccessToken](jwt.serialize(), publicKeysForVerification)
      yield assertTrue(
        result.subject == "test-client-123",
        result.userId.isEmpty, // No userId for client_credentials tokens
        result.clientId == testClientId,
      )
    },
  )
