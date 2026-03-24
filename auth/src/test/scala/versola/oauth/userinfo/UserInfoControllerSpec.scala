package versola.oauth.userinfo

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.userinfo.model.{UserInfoError, UserInfoResponse}
import versola.user.model.UserId
import versola.util.http.{ControllerSpec, NoopTracing}
import versola.util.{CoreConfig, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Date, UUID}

object UserInfoControllerSpec extends UnitSpecBase:

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val clientId1 = ClientId("test-client-1")

  val userInfoResponse = UserInfoResponse(
    claims = Map(
      "sub" -> Json.Str(userId1.toString),
      "name" -> Json.Str("John Doe"),
      "email" -> Json.Str("john@example.com"),
    )
  )

  def createAccessToken(
      userId: UserId,
      clientId: ClientId,
      scope: Set[ScopeToken],
      config: CoreConfig,
  ): String =
    val now = Instant.now()
    val claims = new JWTClaimsSet.Builder()
      .subject(userId.toString)
      .claim("client_id", clientId.toString)
      .claim("scope", scope.map(_.toString).mkString(" "))
      .claim("jti", "test-access-token-id")
      .audience(clientId.toString)
      .issuer(config.jwt.issuer)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(3600)))
      .build()

    val header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID("test-key-id")
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
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        userInfoService = stub[UserInfoService]
        config = TestEnvConfig.coreConfig
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          UserInfoController.routes
            .provideEnvironment(ZEnvironment(userInfoService) ++ ZEnvironment(config) ++ tracing)
        )
        _ <- setup(userInfoService)

        response <- client.batched(request)
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("UserInfoController")(
    suite("GET /v1/userinfo")(
      userInfoTestCase(
        description = "successfully return user info as JSON",
        request = Request.get(
          url = URL.empty / "v1" / "userinfo"
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken.OpenId, ScopeToken("profile")),
              TestEnvConfig.coreConfig,
            )
          )
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
          url = URL.empty / "v1" / "userinfo"
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken.OpenId, ScopeToken("profile")),
              TestEnvConfig.coreConfig,
            )
          )
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
          url = URL.empty / "v1" / "userinfo"
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
          url = URL.empty / "v1" / "userinfo"
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
          url = URL.empty / "v1" / "userinfo"
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken("profile")), // Missing openid scope
              TestEnvConfig.coreConfig,
            )
          )
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
    ),
    suite("POST /v1/userinfo")(
      userInfoTestCase(
        description = "successfully return user info via POST",
        request = Request.post(
          url = URL.empty / "v1" / "userinfo",
          body = Body.empty,
        ).addHeader(
          Header.Authorization.Bearer(
            createAccessToken(
              userId1,
              clientId1,
              Set(ScopeToken.OpenId, ScopeToken("profile")),
              TestEnvConfig.coreConfig,
            )
          )
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

