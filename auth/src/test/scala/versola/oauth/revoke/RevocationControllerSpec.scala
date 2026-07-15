package versola.oauth.revoke

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.revoke.model.RevocationError
import versola.util.{Base64, Secret, UnitSpecBase}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect

import java.time.Instant
import java.util.Date

object RevocationControllerSpec extends UnitSpecBase:

  val clientId1         = ClientId("test-client-1")
  val clientSecret1     = Secret(Array.fill(32)(4.toByte))
  val refreshToken1     = RefreshToken(Array.fill(32)(10.toByte))
  val accessTokenBytes1 = AccessToken(Array.fill(32)(20.toByte))

  def authHeader(clientId: ClientId, secret: Secret): Header.Authorization =
    Header.Authorization.Basic(clientId, Base64.urlEncode(secret))

  def createValidAccessToken(): String =
    val config = TestEnvConfig.coreConfig
    val now    = Instant.now()
    val claims = new JWTClaimsSet.Builder()
      .subject("f077fb08-9935-4a6d-8643-bf97c073bf0f")
      .claim("client_id", clientId1.toString)
      .claim("scope", "read write")
      .claim("jti", Base64.urlEncode(accessTokenBytes1))
      .audience(clientId1.toString)
      .issuer(config.jwt.issuer)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(3600)))
      .build()
    val header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID("test-key-id")
      .`type`(new JOSEObjectType("at+jwt"))
      .build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(TestEnvConfig.privateKey))
    jwt.serialize()

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[RevocationService] => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client            <- ZIO.service[Client]
        revocationService  = stub[RevocationService]
        jwksService        = TestEnvConfig.jwksService
        config             = TestEnvConfig.coreConfig
        tracing           <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            RevocationController.routes
              .provideEnvironment(
                ZEnvironment(revocationService) ++
                  ZEnvironment(jwksService) ++
                  ZEnvironment(config) ++
                  tracing
              )
          )
        )
        _ <- setup(revocationService)

        response     <- client.batched(request)
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("RevocationController")(
    suite("POST /revoke")(
      controllerTestCase(
        description = "return 401 invalid_client when Basic auth credentials are missing",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(refreshToken1))),
        ),
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("invalid_client")),
      ),
      controllerTestCase(
        description = "return 401 invalid_client when token form field is missing",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(Form.fromStrings()),
        ).addHeader(authHeader(clientId1, clientSecret1)),
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("invalid_client")),
      ),
      controllerTestCase(
        description = "return 200 OK when refresh token revocation succeeds",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(refreshToken1))),
        ).addHeader(authHeader(clientId1, clientSecret1)),
        expectedStatus = Status.Ok,
        setup = revocationService =>
          revocationService.revokeRefreshToken.succeedsWith(()),
      ),
      controllerTestCase(
        description = "return 200 OK when access token (JWT) revocation succeeds",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> createValidAccessToken())),
        ).addHeader(authHeader(clientId1, clientSecret1)),
        expectedStatus = Status.Ok,
        setup = revocationService =>
          revocationService.revokeAccessToken.succeedsWith(()),
      ),
      controllerTestCase(
        description = "return 200 OK when refresh token RevocationError is silently ignored (RFC 7009)",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(refreshToken1))),
        ).addHeader(authHeader(clientId1, clientSecret1)),
        expectedStatus = Status.Ok,
        setup = revocationService =>
          revocationService.revokeRefreshToken.failsWith(RevocationError.InvalidClient),
      ),
      controllerTestCase(
        description = "return 200 OK when access token RevocationError is silently ignored (RFC 7009)",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> createValidAccessToken())),
        ).addHeader(authHeader(clientId1, clientSecret1)),
        expectedStatus = Status.Ok,
        setup = revocationService =>
          revocationService.revokeAccessToken.failsWith(RevocationError.InvalidClient),
      ),
      controllerTestCase(
        description = "return 200 OK when access token JWT has invalid signature (JWT.Error silently ignored)",
        request = Request.post(
          url = URL.root / "revoke",
          body = Body.fromURLEncodedForm(
            Form.fromStrings("token" -> "eyJhbGciOiJSUzI1NiIsInR5cCI6ImF0K2p3dCIsImtpZCI6InRlc3Qta2V5LWlkIn0.eyJzdWIiOiJ1c2VyMSJ9.invalidsig")
          ),
        ).addHeader(authHeader(clientId1, clientSecret1)),
        expectedStatus = Status.Ok,
      ),
    ),
  )
