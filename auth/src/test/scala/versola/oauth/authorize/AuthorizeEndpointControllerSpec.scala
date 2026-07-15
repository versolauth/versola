package versola.oauth.authorize

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.AuthId
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod}
import versola.util.http.{NoopTracing, Observability}
import versola.util.UnitSpecBase
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.util.UUID

object AuthorizeEndpointControllerSpec extends UnitSpecBase:

  val clientId  = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val authId    = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val authCode  = AuthorizationCode(Array.fill(16)(1.toByte))
  val config    = TestEnvConfig.coreConfig

  val baseRequest: AuthorizeRequest = AuthorizeRequest(
    clientId           = clientId,
    redirectUri        = redirectUri,
    scope              = Set(ScopeToken("openid")),
    state              = None,
    codeChallenge      = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
    codeChallengeMethod = CodeChallengeMethod.S256,
    responseType       = NonEmptySet(ResponseTypeEntry.Code),
    requestedClaims    = None,
    uiLocales          = None,
    nonce              = None,
    userAgent          = None,
    prompt             = Set.empty,
    maxAge             = None,
    acrValues          = None,
    sessionId          = None,
    loginHint          = None,
  )

  case class Services(
      parser: Stub[AuthorizeRequestParser],
      authService: Stub[AuthorizeEndpointService],
      configService: Stub[OAuthConfigurationService],
  )

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Services => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client        <- ZIO.service[Client]
        parser         = stub[AuthorizeRequestParser]
        authService    = stub[AuthorizeEndpointService]
        configService  = stub[OAuthConfigurationService]
        tracing       <- NoopTracing.layer.build
        services       = Services(parser, authService, configService)
        _             <- TestClient.addRoutes(
          Observability.handleErrors(
            AuthorizeEndpointController.routes
              .provideEnvironment(
                ZEnvironment(parser) ++
                  ZEnvironment(authService) ++
                  ZEnvironment(configService) ++
                  ZEnvironment(config) ++
                  tracing,
              )
          )
        )
        _             <- setup(services)
        response      <- client.batched(request)
        verifyResult  <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("AuthorizeEndpointController")(
    suite("GET /authorize")(
      controllerTestCase(
        description    = "returns BadRequest when parser fails with BadRequest",
        request        = Request.get(URL.root / "authorize"),
        expectedStatus = Status.BadRequest,
        setup          = _.parser.parse.failsWith(Error.BadRequest),
        verify         = resp =>
          resp.body.asString.map(body => assertTrue(body.contains(Error.BadRequest.description))),
      ),
      controllerTestCase(
        description    = "redirects to /challenge and sets cookie on Initialize response",
        request        = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup          = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify         = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.path.encode.contains("challenge")),
            resp.header(Header.SetCookie).isDefined,
          )),
      ),
      controllerTestCase(
        description    = "redirects to redirect_uri with code on Authorized response",
        request        = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup          = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Authorized(authCode, None))
          yield (),
        verify         = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.encode.startsWith("https://example.com/callback")),
            resp.header(Header.Location).exists(_.url.encode.contains("code=")),
          )),
      ),
    ),
    suite("POST /authorize")(
      controllerTestCase(
        description    = "returns BadRequest when parser fails with BadRequest",
        request        = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.BadRequest,
        setup          = _.parser.parse.failsWith(Error.BadRequest),
        verify         = resp =>
          resp.body.asString.map(body => assertTrue(body.contains(Error.BadRequest.description))),
      ),
      controllerTestCase(
        description    = "redirects to /challenge and sets cookie on Initialize response",
        request        = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.SeeOther,
        setup          = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify         = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.path.encode.contains("challenge")),
            resp.header(Header.SetCookie).isDefined,
          )),
      ),
      controllerTestCase(
        description    = "redirects to redirect_uri with code on Authorized response",
        request        = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.SeeOther,
        setup          = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Authorized(authCode, None))
          yield (),
        verify         = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.encode.startsWith("https://example.com/callback")),
            resp.header(Header.Location).exists(_.url.encode.contains("code=")),
          )),
      ),
    ),
  )
