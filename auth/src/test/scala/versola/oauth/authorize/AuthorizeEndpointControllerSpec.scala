package versola.oauth.authorize

import org.scalamock.stubs.Stub
import versola.audit.{AuthActors, AuthAudit, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.Prompt
import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseMode, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.AuthId
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod}
import versola.util.UnitSpecBase
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.util.UUID

object AuthorizeEndpointControllerSpec extends UnitSpecBase:

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val authId = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val authCode = AuthorizationCode(Array.fill(16)(1.toByte))
  val config = TestEnvConfig.coreConfig

  val baseRequest: AuthorizeRequest = AuthorizeRequest(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("openid")),
    state = None,
    codeChallenge = Some(CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")),
    codeChallengeMethod = Some(CodeChallengeMethod.S256),
    responseType = NonEmptySet(ResponseTypeEntry.Code),
    responseMode = ResponseMode.Query,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    userAgent = None,
    userAgentCookie = None,
    prompt = Set.empty,
    maxAge = None,
    acrValues = None,
    sessionId = None,
    loginHint = None,
    idTokenHint = None,
    resources = Nil,
    authorizationDetails = None,
    dpopJkt = None,
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
      verifyAudit: List[AuditDraft] => TestResult = _ => assertCompletes,
      tiers: AuthAudit.Tiers = CapturingAuditEvents.tiers,
      audit: CapturingAuditEvents = CapturingAuditEvents.shared(),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        parser = stub[AuthorizeRequestParser]
        authService = stub[AuthorizeEndpointService]
        configService = stub[OAuthConfigurationService]
        responseService = AuthorizationResponseService.Impl(config, configService, TestEnvConfig.jwksService)
        tracing <- NoopTracing.layer.build
        services = Services(parser, authService, configService)
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AuthorizeEndpointController.routes
              .provideEnvironment(
                ZEnvironment(parser) ++
                  ZEnvironment(authService) ++
                  ZEnvironment(configService) ++
                  ZEnvironment[AuthorizationResponseService](responseService) ++
                  ZEnvironment(config) ++
                  ZEnvironment[AuditEvents](audit) ++
                  ZEnvironment(tiers) ++
                  tracing,
              ),
          ),
        )
        _ <- setup(services)
        response <- client.batched(request)
        verifyResult <- verify(response)
        drafts <- audit.recorded
      yield assertTrue(response.status == expectedStatus) && verifyResult && verifyAudit(drafts)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("AuthorizeEndpointController")(
    suite("GET /authorize")(
      controllerTestCase(
        description = "returns BadRequest when parser fails with BadRequest",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.BadRequest,
        setup = _.parser.parse.failsWith(Error.BadRequest),
        verify = resp =>
          resp.body.asString.map(body => assertTrue(body.contains(Error.BadRequest.description))),
      ),
      controllerTestCase(
        description = "returns invalid_request_object in the body when the request object fails verification",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.BadRequest,
        setup = _.parser.parse.failsWith(Error.InvalidRequestObject("jti replayed")),
        verify = resp =>
          resp.body.asString.map(body =>
            assertTrue(body.contains(s"\"error\":\"${Error.InvalidRequestObject.error}\"")),
          ),
      ),
      controllerTestCase(
        description = "redirects to /challenge and sets cookie on Initialize response",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.path.encode.contains("challenge")),
            resp.header(Header.SetCookie).isDefined,
          )),
      ),
      controllerTestCase(
        description = "redirects to redirect_uri with code on Authorized response",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Authorized(authCode, None))
          yield (),
        verify = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.encode.startsWith("https://example.com/callback")),
            resp.header(Header.Location).exists(_.url.encode.contains("code=")),
          )),
      ),
    ),
    suite("POST /authorize")(
      controllerTestCase(
        description = "returns BadRequest when parser fails with BadRequest",
        request = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.BadRequest,
        setup = _.parser.parse.failsWith(Error.BadRequest),
        verify = resp =>
          resp.body.asString.map(body => assertTrue(body.contains(Error.BadRequest.description))),
      ),
      controllerTestCase(
        description = "returns invalid_request_object in the body when the request object fails verification",
        request = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.BadRequest,
        setup = _.parser.parse.failsWith(Error.InvalidRequestObject("jti replayed")),
        verify = resp =>
          resp.body.asString.map(body =>
            assertTrue(body.contains(s"\"error\":\"${Error.InvalidRequestObject.error}\"")),
          ),
      ),
      controllerTestCase(
        description = "redirects to /challenge and sets cookie on Initialize response",
        request = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.path.encode.contains("challenge")),
            resp.header(Header.SetCookie).isDefined,
          )),
      ),
      controllerTestCase(
        description = "redirects to redirect_uri with code on Authorized response",
        request = Request.post(URL.root / "authorize", Body.empty),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Authorized(authCode, None))
          yield (),
        verify = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.encode.startsWith("https://example.com/callback")),
            resp.header(Header.Location).exists(_.url.encode.contains("code=")),
          )),
      ),
    ),
    suite("audit log (authorize.request)")(
      controllerTestCase(
        description = "a request that starts a conversation records authorize.request success with what it asked for; the client is the actor",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest.copy(
              scope = Set(ScopeToken("profile"), ScopeToken("openid")),
              prompt = Set(Prompt.login),
            ))
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verifyAudit = drafts =>
          assertTrue(
            drafts.map(d => (d.kind, d.outcome, d.reason)) == List((AuthAuditKinds.AuthorizeRequest, AuditOutcome.success, None)),
            drafts.head.actor == Some((AuthActors.client, Some(clientId: String))),
            drafts.head.details == AuthAuditDetails.AuthorizeRequest(
              responseType = List("Code"),
              scope = List("openid", "profile"),
              resource = Nil,
              prompt = List("login"),
              acrValues = Nil,
              par = false,
            ),
          ),
      ),
      controllerTestCase(
        description = "a request redeeming a pushed request_uri is recorded as pushed",
        request = Request.get((URL.root / "authorize").addQueryParam("request_uri", "urn:ietf:params:oauth:request_uri:abc")),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verifyAudit = drafts => assertTrue(drafts.map(_.details).collect { case d: AuthAuditDetails.AuthorizeRequest => d.par } == List(true)),
      ),
      controllerTestCase(
        description = "a success is left out when the deployment turned audit.events.authorize-success off",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        tiers = CapturingAuditEvents.tiers.copy(authorizeSuccess = false),
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verifyAudit = drafts => assertTrue(drafts.isEmpty),
      ),
      controllerTestCase(
        description = "a request that does not parse records authorize.request failure invalid_request, even with successes left out",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.BadRequest,
        tiers = CapturingAuditEvents.tiers.copy(authorizeSuccess = false),
        setup = _.parser.parse.failsWith(Error.BadRequest),
        verifyAudit = drafts =>
          assertTrue(drafts.map(d => (d.kind, d.outcome, d.reason)) == List((
            AuthAuditKinds.AuthorizeRequest,
            AuditOutcome.failure,
            Some("invalid_request"),
          ))),
      ),
      controllerTestCase(
        description = "a request object that fails verification records invalid_request_object",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.BadRequest,
        setup = _.parser.parse.failsWith(Error.InvalidRequestObject("jti replayed")),
        verifyAudit = drafts =>
          assertTrue(
            drafts.map(_.reason) == List(Some(Error.InvalidRequestObject.error)),
            // what the log tells is the code, not the reason the object was refused
            !drafts.head.details.toJson.toString.contains("jti replayed"),
          ),
      ),
      controllerTestCase(
        description = "an error returned to the client by redirect is still a failure, with the client it was returned to",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          services.parser.parse.failsWith(Error.StateInvalid(clientId, redirectUri, ResponseMode.Query)) *>
            services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900)),
        verifyAudit = drafts =>
          assertTrue(
            drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.failure, Some("invalid_request"))),
            drafts.head.actor == Some((AuthActors.client, Some(clientId: String))),
          ),
      ),
      controllerTestCase(
        description = "a request is refused with 503 when the log cannot take the event, and no conversation is started",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.ServiceUnavailable,
        audit = CapturingAuditEvents.shared(unavailable = true),
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify = resp => ZIO.succeed(assertTrue(resp.header(Header.SetCookie).isEmpty)),
      ),
    ),
  )
