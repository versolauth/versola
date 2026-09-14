package versola.oauth.userinfo

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken, TenantId}
import versola.oauth.dpop.{DpopService, EdgeAssertionService}
import versola.oauth.jwks.JwksService
import versola.oauth.userinfo.model.{UserInfoError, UserInfoResponse}
import versola.user.model.UserId
import versola.util.http.{ControllerSpec, NoopTracing, Observability}
import versola.util.{CoreConfig, Dpop, EdgeAssertion, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Date, UUID}

object UserInfoControllerSpec extends UnitSpecBase:

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val clientId1 = ClientId("test-client-1")
  val tenantId1 = TenantId("tenant-1")
  val boundJkt1 = "0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I"

  /** The record `checkDpop` looks up to learn which tenant an edge assertion for this token's
    * client has to be scoped to -- see `EdgeAssertionService.verify`. */
  val client1 = OAuthClientRecord(
    id = clientId1,
    tenantId = tenantId1,
    clientName = Map("en" -> "Test Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken.OpenId),
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
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
  )

  val userInfoResponse = UserInfoResponse(
    claims = Map(
      "sub" -> Json.Str(userId1.toString),
      "name" -> Json.Str("John Doe"),
      "email" -> Json.Str("john@example.com"),
    ),
  )

  def createAccessToken(
      userId: UserId,
      clientId: ClientId,
      scope: Set[ScopeToken],
      config: CoreConfig,
      cnfJkt: Option[String] = None,
  ): String =
    val now = Instant.now()
    val builder = new JWTClaimsSet.Builder()
      .subject(userId.toString)
      .claim("client_id", clientId.toString)
      .claim("scope", scope.map(_.toString).mkString(" "))
      .claim("jti", "test-access-token-id")
      .audience("https://api.example.com")
      .issuer(config.jwt.issuer)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(3600)))
    cnfJkt.foreach(jkt => builder.claim("cnf", java.util.Map.of("jkt", jkt)))
    val claims = builder.build()

    val header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID("test-key-id")
      .`type`(new JOSEObjectType("at+jwt"))
      .build()

    val jwt = new SignedJWT(header, claims)
    val signer = new RSASSASigner(config.jwt.privateKey)
    jwt.sign(signer)
    jwt.serialize()

  def userInfoTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[UserInfoService] => UIO[Unit] = _ => ZIO.unit,
      dpopSetup: Stub[DpopService] => UIO[Unit] = _ => ZIO.unit,
      edgeAssertionSetup: Stub[EdgeAssertionService] => UIO[Unit] = _.verify.succeedsWith(None),
      // The lookups `checkDpop` makes against the token's client: the tenant an edge assertion
      // has to be scoped to, and (RFC 9449 §8) whether that client's tenant demands a nonce.
      // Defaults to the fixture client every test above assumes, with no nonce required.
      oAuthConfigurationSetup: Stub[OAuthConfigurationService] => UIO[Unit] = service =>
        service.find.succeedsWith(Some(client1)) *> service.requireDpopNonce.succeedsWith(false),
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      verifyDpop: Stub[DpopService] => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      config: CoreConfig = TestEnvConfig.coreConfig,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        userInfoService = stub[UserInfoService]
        dpopService = stub[DpopService]
        edgeAssertionService = stub[EdgeAssertionService]
        oAuthConfigurationService = stub[OAuthConfigurationService]
        jwksService = TestEnvConfig.jwksService
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserInfoController.routes
              .provideEnvironment(
                ZEnvironment(userInfoService) ++ ZEnvironment(config) ++ ZEnvironment(jwksService) ++
                  ZEnvironment(dpopService) ++ ZEnvironment(edgeAssertionService) ++
                  ZEnvironment(oAuthConfigurationService) ++ tracing,
              ),
          ),
        )
        _ <- setup(userInfoService)
        _ <- dpopSetup(dpopService)
        _ <- edgeAssertionSetup(edgeAssertionService)
        _ <- oAuthConfigurationSetup(oAuthConfigurationService)

        response <- client.batched(request)
        verifyResult <- verify(response)
        verifyDpopResult <- verifyDpop(dpopService)
      yield assertTrue(response.status == expectedStatus) && verifyResult && verifyDpopResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("UserInfoController")(
    suite("GET /userinfo")(
      userInfoTestCase(
        description = "successfully return user info as JSON",
        request = Request.get(
          url = URL.empty / "userinfo",
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken.OpenId, ScopeToken("profile")),
              TestEnvConfig.coreConfig,
            ),
          ),
        ),
        expectedStatus = Status.Ok,
        setup = userInfoService =>
          userInfoService.getUserInfo.succeedsWith(userInfoResponse),
        verify = response =>
          for
            body <- response.body.asString
            userInfo <- ZIO.fromEither(body.fromJson[UserInfoResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            userInfo.claims.contains("sub"),
            userInfo.claims.get("sub").contains(Json.Str(userId1.toString)),
            userInfo.claims.contains("name"),
            userInfo.claims.contains("email"),
          ),
      ),
      userInfoTestCase(
        description = "successfully return user info as JWT when Accept: application/jwt",
        request = Request.get(
          url = URL.empty / "userinfo",
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken.OpenId, ScopeToken("profile")),
              TestEnvConfig.coreConfig,
            ),
          ),
        ).addHeader(Header.Accept(MediaType.application.jwt)),
        expectedStatus = Status.Ok,
        setup = userInfoService =>
          userInfoService.getUserInfo.succeedsWith(userInfoResponse),
        verify = response =>
          for
            contentType <- ZIO.fromOption(response.header(Header.ContentType))
              .orElseFail(new RuntimeException("Missing Content-Type header"))
            body <- response.body.asString
          yield assertTrue(
            contentType.mediaType == MediaType.application.jwt,
            body.nonEmpty,
            body.split("\\.").length == 3, // JWT has 3 parts
          ),
      ),
      userInfoTestCase(
        description = "fail with Unauthorized when Bearer token is missing",
        request = Request.get(
          url = URL.empty / "userinfo",
        ),
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for
            wwwAuth <- ZIO.fromOption(response.header(Header.WWWAuthenticate))
              .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
          yield assertTrue(
            wwwAuth.renderedValue.contains("Bearer"),
            wwwAuth.renderedValue.contains("invalid_request"),
          ),
      ),
      userInfoTestCase(
        description = "fail with Unauthorized when access token is invalid",
        request = Request.get(
          url = URL.empty / "userinfo",
        ).addHeader(Header.Authorization.Bearer("invalid.jwt.token")),
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for
            wwwAuth <- ZIO.fromOption(response.header(Header.WWWAuthenticate))
              .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
          yield assertTrue(
            wwwAuth.renderedValue.contains("invalid_token"),
          ),
      ),
      userInfoTestCase(
        description = "fail with Unauthorized when token has insufficient scope (missing openid)",
        request = Request.get(
          url = URL.empty / "userinfo",
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken("profile")), // Missing openid scope
              TestEnvConfig.coreConfig,
            ),
          ),
        ),
        expectedStatus = Status.Unauthorized,
        // No setup needed - controller checks scope before calling service
        verify = response =>
          for
            wwwAuth <- ZIO.fromOption(response.header(Header.WWWAuthenticate))
              .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
          yield assertTrue(
            wwwAuth.renderedValue.contains("insufficient_scope"),
          ),
      ),
      locally {
        val boundAccessToken = createAccessToken(
          userId1,
          clientId1,
          Set(ScopeToken.OpenId),
          TestEnvConfig.coreConfig,
          cnfJkt = Some(boundJkt1),
        )
        userInfoTestCase(
          description = "successfully return user info for a DPoP-bound token presented with a valid proof",
          request = Request.get(url = URL.empty / "userinfo")
            .addHeader(Header.Custom("Authorization", s"DPoP $boundAccessToken"))
            .addHeader(Header.Custom("DPoP", "proof-jwt-placeholder")),
          expectedStatus = Status.Ok,
          setup = userInfoService => userInfoService.getUserInfo.succeedsWith(userInfoResponse),
          dpopSetup = dpopService =>
            dpopService.verify.succeedsWith(
              Dpop.Proof(
                jkt = boundJkt1,
                jti = "proof-jti-1",
                iat = Instant.now(),
                nonce = None,
                ath = Some(Dpop.ath(boundAccessToken)),
              ),
            ),
          verify = response =>
            for
              body <- response.body.asString
              userInfo <- ZIO.fromEither(body.fromJson[UserInfoResponse]).mapError(new RuntimeException(_))
            yield assertTrue(userInfo.claims.contains("sub")),
        )
      },
      locally {
        val boundAccessToken = createAccessToken(
          userId1,
          clientId1,
          Set(ScopeToken.OpenId),
          TestEnvConfig.coreConfig,
          cnfJkt = Some(boundJkt1),
        )
        val boundRequest = Request.get(url = URL.empty / "userinfo")
          .addHeader(Header.Custom("Authorization", s"DPoP $boundAccessToken"))
          .addHeader(Header.Custom("DPoP", "proof-jwt-placeholder"))
        def boundProof(nonce: Option[String]) = Dpop.Proof(
          jkt = boundJkt1,
          jti = "proof-jti-1",
          iat = Instant.now(),
          nonce = nonce,
          ath = Some(Dpop.ath(boundAccessToken)),
        )
        suite("DPoP nonce")(
          // RFC 9449 §8 is the tenant setting of the client the token was issued to rather than
          // a property of this endpoint, so what has to be asserted is that the setting is what
          // reaches the check.
          userInfoTestCase(
            description = "do not demand a nonce unless the token's client tenant asks for one",
            request = boundRequest,
            expectedStatus = Status.Ok,
            setup = userInfoService => userInfoService.getUserInfo.succeedsWith(userInfoResponse),
            dpopSetup = _.verify.succeedsWith(boundProof(nonce = None)),
            verifyDpop = dpopService =>
              ZIO.succeed(assertTrue(dpopService.verify.calls.map(_._4) == List(false))),
          ),
          userInfoTestCase(
            description = "require a nonce on every proof once the token's client tenant asks for one",
            request = boundRequest,
            expectedStatus = Status.Ok,
            setup = userInfoService => userInfoService.getUserInfo.succeedsWith(userInfoResponse),
            dpopSetup = _.verify.succeedsWith(boundProof(nonce = Some("srv-nonce"))),
            verifyDpop = dpopService =>
              ZIO.succeed(assertTrue(dpopService.verify.calls.map(_._4) == List(true))),
            oAuthConfigurationSetup = service =>
              service.find.succeedsWith(Some(client1)) *> service.requireDpopNonce.succeedsWith(true),
          ),
          // §9: the nonce travels in its own header rather than in the challenge, and the client
          // is expected to retry once over it -- so the refusal has to carry both.
          userInfoTestCase(
            description = "answer a nonce challenge with use_dpop_nonce and serve the nonce in DPoP-Nonce",
            request = boundRequest,
            expectedStatus = Status.Unauthorized,
            dpopSetup = _.verify.failsWith(DpopService.Error.NonceRequired("fresh-nonce")),
            verify = response =>
              ZIO.succeed(assertTrue(
                response.headers.get("WWW-Authenticate").exists(_.contains("use_dpop_nonce")),
                response.headers.get("DPoP-Nonce").contains("fresh-nonce"),
              )),
            oAuthConfigurationSetup = service =>
              service.find.succeedsWith(Some(client1)) *> service.requireDpopNonce.succeedsWith(true),
          ),
        )
      },
      locally {
        val boundAccessToken = createAccessToken(
          userId1,
          clientId1,
          Set(ScopeToken.OpenId),
          TestEnvConfig.coreConfig,
          cnfJkt = Some(boundJkt1),
        )
        // The call edge makes on its own behalf for `fetchUserInfo`: the user's bound token,
        // no proof (edge cannot mint one for the client's key), and an assertion that edge
        // already enforced §7 at its own boundary.
        userInfoTestCase(
          description = "honour a bound token under Bearer when a valid edge assertion vouches for it",
          request = Request.get(url = URL.empty / "userinfo")
            .addHeader(Header.Authorization.Bearer(boundAccessToken))
            .addHeader(Header.Custom(EdgeAssertion.HeaderName, "edge-assertion-jwt")),
          expectedStatus = Status.Ok,
          setup = userInfoService => userInfoService.getUserInfo.succeedsWith(userInfoResponse),
          edgeAssertionSetup = _.verify.succeedsWith(Some("edge-1")),
          verify = response =>
            for
              body <- response.body.asString
              userInfo <- ZIO.fromEither(body.fromJson[UserInfoResponse]).mapError(new RuntimeException(_))
            yield assertTrue(userInfo.claims.contains("sub")),
        )
      },
      locally {
        val boundAccessToken = createAccessToken(
          userId1,
          clientId1,
          Set(ScopeToken.OpenId),
          TestEnvConfig.coreConfig,
          cnfJkt = Some(boundJkt1),
        )
        userInfoTestCase(
          description = "refuse a bound token under Bearer when the edge assertion does not verify",
          request = Request.get(url = URL.empty / "userinfo")
            .addHeader(Header.Authorization.Bearer(boundAccessToken))
            .addHeader(Header.Custom(EdgeAssertion.HeaderName, "not-a-valid-assertion")),
          expectedStatus = Status.Unauthorized,
          edgeAssertionSetup = _.verify.succeedsWith(None),
          verify = response =>
            for
              wwwAuth <- ZIO.fromOption(response.rawHeader("WWW-Authenticate"))
                .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
            yield assertTrue(wwwAuth.contains("invalid_dpop_proof")),
        )
      },
      locally {
        val boundAccessToken = createAccessToken(
          userId1,
          clientId1,
          Set(ScopeToken.OpenId),
          TestEnvConfig.coreConfig,
          cnfJkt = Some(boundJkt1),
        )
        // Two assertions is two identity claims; honouring either silently admits the one
        // that was never checked, so the header is read as absent instead.
        userInfoTestCase(
          description = "refuse a bound token under Bearer carrying more than one edge assertion",
          request = Request.get(url = URL.empty / "userinfo")
            .addHeader(Header.Authorization.Bearer(boundAccessToken))
            .addHeader(Header.Custom(EdgeAssertion.HeaderName, "first-assertion"))
            .addHeader(Header.Custom(EdgeAssertion.HeaderName, "second-assertion")),
          expectedStatus = Status.Unauthorized,
          edgeAssertionSetup = _.verify.succeedsWith(Some("edge-1")),
          verify = response =>
            for
              wwwAuth <- ZIO.fromOption(response.rawHeader("WWW-Authenticate"))
                .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
            yield assertTrue(wwwAuth.contains("invalid_dpop_proof")),
        )
      },
      userInfoTestCase(
        description = "fail with invalid_dpop_proof when a DPoP-bound token is presented under the Bearer scheme",
        request = Request.get(url = URL.empty / "userinfo")
          .addHeader(
            Header.Authorization.Bearer(
              createAccessToken(
                userId1,
                clientId1,
                Set(ScopeToken.OpenId),
                TestEnvConfig.coreConfig,
                cnfJkt = Some(boundJkt1),
              ),
            ),
          ),
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for
            wwwAuth <- ZIO.fromOption(response.rawHeader("WWW-Authenticate"))
              .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
          yield assertTrue(
            wwwAuth.contains("DPoP"),
            wwwAuth.contains("invalid_dpop_proof"),
          ),
      ),
      userInfoTestCase(
        description = "fail with invalid_dpop_proof when a DPoP-scheme request for a bound token carries no proof header",
        request =
          val accessToken = createAccessToken(
            userId1,
            clientId1,
            Set(ScopeToken.OpenId),
            TestEnvConfig.coreConfig,
            cnfJkt = Some(boundJkt1),
          )
          Request.get(url = URL.empty / "userinfo")
            .addHeader(Header.Custom("Authorization", s"DPoP $accessToken"))
        ,
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for
            wwwAuth <- ZIO.fromOption(response.rawHeader("WWW-Authenticate"))
              .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
          yield assertTrue(
            wwwAuth.contains("invalid_dpop_proof"),
          ),
      ),
      userInfoTestCase(
        description = "fail with invalid_dpop_proof when the proof's ath names a different access token",
        request =
          val accessToken = createAccessToken(
            userId1,
            clientId1,
            Set(ScopeToken.OpenId),
            TestEnvConfig.coreConfig,
            cnfJkt = Some(boundJkt1),
          )
          Request.get(url = URL.empty / "userinfo")
            .addHeader(Header.Custom("Authorization", s"DPoP $accessToken"))
            .addHeader(Header.Custom("DPoP", "proof-jwt-placeholder"))
        ,
        expectedStatus = Status.Unauthorized,
        dpopSetup = dpopService =>
          dpopService.verify.succeedsWith(
            Dpop.Proof(
              jkt = boundJkt1,
              jti = "proof-jti-1",
              iat = Instant.now(),
              nonce = None,
              // A genuine proof, but made over a different access token -- exactly what a
              // stolen bound token paired with an unrelated valid proof looks like.
              ath = Some(Dpop.ath("some-other-access-token")),
            ),
          ),
        verify = response =>
          for
            wwwAuth <- ZIO.fromOption(response.rawHeader("WWW-Authenticate"))
              .orElseFail(new RuntimeException("Missing WWW-Authenticate header"))
          yield assertTrue(
            wwwAuth.contains("invalid_dpop_proof"),
          ),
      ),
    ),
    suite("POST /userinfo")(
      userInfoTestCase(
        description = "successfully return user info via POST",
        request = Request.post(
          url = URL.empty / "userinfo",
          body = Body.empty,
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken.OpenId, ScopeToken("profile")),
              TestEnvConfig.coreConfig,
            ),
          ),
        ),
        expectedStatus = Status.Ok,
        setup = userInfoService =>
          userInfoService.getUserInfo.succeedsWith(userInfoResponse),
        verify = response =>
          for
            body <- response.body.asString
            userInfo <- ZIO.fromEither(body.fromJson[UserInfoResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            userInfo.claims.contains("sub"),
          ),
      ),
    ),
  )
