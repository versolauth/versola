package versola.oauth.authorize

import org.scalamock.stubs.Stub
import versola.audit.{AuthActors, AuthAudit, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents, ClientAuthFailures, ImmediateClientAuthFailures}
import versola.auth.TestEnvConfig
import versola.auth.model.Password
import versola.oauth.authorize.model.Prompt
import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseMode, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthMethodRef, ClientId, PassedFactorRecord, ScopeToken, TenantId}
import versola.oauth.clientauth.ClientAssertionRepository
import versola.oauth.conversation.model.AuthId
import versola.oauth.conversation.{
  ConversationRenderService,
  ConversationResult,
  ConversationRouter,
  ConversationRouterSpec,
  LoginPasswordSubmission,
  PasskeyAssertionSubmission,
}
import versola.oauth.handoff.CellHandoff
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod, ConversationCookie}
import versola.oauth.session.model.{PublicSessionId, SupersededSession}
import versola.user.model.{Login, UserId}
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.{NoopTracing, Observability}
import versola.util.{Email, Phone, UnitSpecBase}
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

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
    ip = None,
  )

  case class Services(
      parser: Stub[AuthorizeRequestParser],
      authService: Stub[AuthorizeEndpointService],
      configService: Stub[OAuthConfigurationService],
      handoff: Stub[CellHandoff],
      assertions: Stub[ClientAssertionRepository],
      router: Stub[ConversationRouter],
      renderService: Stub[ConversationRenderService],
  )

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Services => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      verifyAudit: List[AuditDraft] => TestResult = _ => assertCompletes,
      audit: CapturingAuditEvents = CapturingAuditEvents.shared(),
      verifyServices: Services => TestResult = _ => assertCompletes,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        parser = stub[AuthorizeRequestParser]
        authService = stub[AuthorizeEndpointService]
        configService = stub[OAuthConfigurationService]
        handoff = stub[CellHandoff]
        assertions = stub[ClientAssertionRepository]
        router = stub[ConversationRouter]
        renderService = stub[ConversationRenderService]
        responseService = AuthorizationResponseService.Impl(config, configService, TestEnvConfig.jwksService)
        tracing <- NoopTracing.layer.build
        services = Services(parser, authService, configService, handoff, assertions, router, renderService)
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
                  ZEnvironment[ClientAuthFailures](ImmediateClientAuthFailures(audit)) ++
                  ZEnvironment[CellHandoff](handoff) ++
                  ZEnvironment(assertions) ++
                  ZEnvironment(router) ++
                  ZEnvironment(renderService) ++
                  tracing,
              ),
          ),
        )
        _ <- setup(services)
        response <- client.batched(request)
        verifyResult <- verify(response)
        drafts <- audit.recorded
      yield assertTrue(response.status == expectedStatus) && verifyResult && verifyAudit(drafts) && verifyServices(services)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val handedCookie = "first-cells-conversation-cookie"
  private val handedTrace = ConcurrentLinkedQueue[String]()
  private val supersededSession =
    SupersededSession(
      userId = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000c1")),
      sessionId = PublicSessionId("kz-0.first-cells-session"),
    )

  private def submission(cookie: String, passkeyRequest: Option[String] = None) =
    CellHandoff.Submission(id = "sub-1", cookie = CellHandoff.cookieDigest(cookie), passkeyRequest = passkeyRequest)

  private def payload(submission: Option[CellHandoff.Submission], superseded: Option[SupersededSession] = None) =
    CellHandoff.Payload(
      params = Map("client_id" -> List("test-client")),
      from = "kz-2",
      to = "kz-1",
      expiresAt = 1000L,
      submission = submission,
      superseded = superseded,
    )

  /** The browser repeating a submission at the cell the first cell sent it to.
    *
    * @param opened whether the seal opens for this cell
    * @param taken what the store of seals answers to this one: true the first time
    * @param cookie the conversation cookie the browser presents
    */
  def handedTestCase(
      description: String,
      payload: CellHandoff.Payload,
      expectedStatus: Status,
      form: Form = Form.fromStrings("login" -> "user", "password" -> "s3cret", "csrf" -> "first-cells-csrf"),
      cookie: Option[String] = Some(handedCookie),
      opened: Boolean = true,
      taken: Boolean = true,
      trace: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String](),
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      verifyServices: Services => TestResult = _ => assertCompletes,
  ) =
    val request = cookie.foldLeft(
      Request.post(
        (URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed"),
        Body.fromURLEncodedForm(form),
      ),
    )((request, content) => request.addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(ConversationCookie.name, content)))))
    controllerTestCase(
      description = description,
      request = request,
      expectedStatus = expectedStatus,
      setup = services =>
        for
          _ <- if opened then services.handoff.open.succeedsWith(payload) else services.handoff.open.failsWith(CellHandoff.Refused.Elsewhere)
          _ <- services.assertions.recordIfAbsent.succeedsWith(taken)
          _ <- services.parser.validate.succeedsWith(baseRequest)
          _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
          _ <- services.configService.getIpHeader.succeedsWith("X-Real-IP")
          _ <- services.configService.getPasswordRegex.succeedsWith(".*")
          _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          _ = services.router.supersedes.returnsWith(ZIO.succeed(trace.add("supersedes")).unit)
          _ = services.router.submitHanded.returnsWith(
            ZIO.succeed(trace.add("submitHanded")).as(
              (ConversationResult.RenderStep(ConversationRouterSpec.initialRecord.step), ConversationRouterSpec.initialRecord),
            ),
          )
          _ <- services.renderService.renderSubmit.succeedsWith(Response.seeOther(URL.root / "challenge"))
        yield (),
      verify = verify,
      verifyServices = verifyServices,
    )

  private val registeredCodeVerified = PassedFactorRecord(Instant.ofEpochSecond(500L), Set(AuthMethodRef.otp, AuthMethodRef.sms))
  private val registeredTenant = TenantId("tenant-1")
  private val handedRegistration =
    CellHandoff.Registered(credential = Right(Phone("+15551234567")), verified = registeredCodeVerified, tenantId = registeredTenant)

  private def registrationPayload(
      superseded: Option[SupersededSession] = None,
      email: Option[Email] = None,
      phone: Option[Phone] = Some(Phone("+15551234567")),
  ) =
    CellHandoff.RegistrationPayload(
      params = Map("client_id" -> List("test-client")),
      from = "kz-2",
      to = "kz-1",
      expiresAt = 1000L,
      id = "reg-1",
      cookie = CellHandoff.cookieDigest(handedCookie),
      email = email,
      phone = phone,
      verified = registeredCodeVerified,
      tenantId = registeredTenant,
      superseded = superseded,
    )

  /** The browser arriving at the cell that takes users, with the verified registration the first cell sealed.
    *
    * @param opened what opening the seal answers: the payload, or why not
    * @param taken what the store of seals answers to this one: true the first time
    * @param cookie the conversation cookie the browser presents
    */
  def registeredTestCase(
      description: String,
      expectedStatus: Status,
      opened: Either[CellHandoff.Refused, CellHandoff.RegistrationPayload] = Right(registrationPayload()),
      cookie: Option[String] = Some(handedCookie),
      taken: Boolean = true,
      validated: Either[Error, AuthorizeRequest] = Right(baseRequest),
      trace: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String](),
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      verifyServices: Services => TestResult = _ => assertCompletes,
  ) =
    val request = cookie.foldLeft(
      Request.get((URL.root / "challenge" / "registration").addQueryParam(CellHandoff.RegistrationParameter, "v1.kz-1.sealed")),
    )((request, content) => request.addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(ConversationCookie.name, content)))))
    controllerTestCase(
      description = description,
      request = request,
      expectedStatus = expectedStatus,
      setup = services =>
        for
          _ <- opened.fold(services.handoff.openRegistration.failsWith(_), services.handoff.openRegistration.succeedsWith(_))
          _ <- services.assertions.recordIfAbsent.succeedsWith(taken)
          _ <- validated.fold(services.parser.validate.failsWith(_), services.parser.validate.succeedsWith(_))
          _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
          _ <- services.configService.getIpHeader.succeedsWith("X-Real-IP")
          _ <- services.authService.authorizeHanded.succeedsWith(AuthorizeResponse.Initialize(authId))
          _ = services.router.supersedes.returnsWith(ZIO.succeed(trace.add("supersedes")).unit)
          _ = services.router.submitRegistered.returnsWith(
            ZIO.succeed(trace.add("submitRegistered")).as(
              (ConversationResult.RenderStep(ConversationRouterSpec.initialRecord.step), ConversationRouterSpec.initialRecord),
            ),
          )
          _ <- services.renderService.renderSubmit.succeedsWith(Response.seeOther(URL.root / "challenge"))
        yield (),
      verify = verify,
      verifyServices = verifyServices,
    )

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
        description = "redirects to the home cell and sets no cookie when the hinted address belongs to a user of another cell",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(
              AuthorizeResponse.HandedOff(URL.decode("/challenge/handoff?handoff=v1.kz-2.sealed").toOption.get),
            )
          yield (),
        verify = resp =>
          ZIO.succeed(
            assertTrue(
              resp.header(Header.Location).map(_.url.encode).contains("/challenge/handoff?handoff=v1.kz-2.sealed"),
              resp.header(Header.SetCookie).isEmpty,
            ),
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
      // versolauth/versola#1046: a sign-in another cell sealed for this one.
      controllerTestCase(
        description = "a handoff sealed for this cell is validated as an authorization request and starts the conversation here",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed")),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.handoff.open.succeedsWith(
              CellHandoff.Payload(
                params = Map("client_id" -> List("test-client"), "login_hint" -> List("user@example.com")),
                from = "kz-2",
                to = "kz-1",
                expiresAt = 0L,
                submission = None,
                superseded = None,
              ),
            )
            _ <- services.parser.validate.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorizeHanded.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.path.encode.contains("challenge")),
            resp.header(Header.SetCookie).isDefined,
          )),
        // versolauth/versola#1052: it was sent here by a cell that has chosen, so the hinted address is not sent on by asking
        // central again.
        verifyServices = services =>
          assertTrue(services.authService.authorizeHanded.calls == List(baseRequest), services.authService.authorize.calls.isEmpty),
      ),
      controllerTestCase(
        description = "a handoff whose hinted address this cell is told to hand on is answered with the redirect it is given, and no cookie",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed")),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.handoff.open.succeedsWith(payload(submission = None, superseded = None))
            _ <- services.parser.validate.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorizeHanded.succeedsWith(
              AuthorizeResponse.HandedOff(URL.decode("/challenge/handoff?handoff=v1.kz-3.sealed").toOption.get),
            )
          yield (),
        verify = resp =>
          ZIO.succeed(
            assertTrue(
              resp.header(Header.Location).map(_.url.encode).contains("/challenge/handoff?handoff=v1.kz-3.sealed"),
              resp.header(Header.SetCookie).isEmpty,
            ),
          ),
      ),
      controllerTestCase(
        description = "a plain /authorize with a login hint does not take the handed path",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest.copy(loginHint = Some(Left(versola.util.Email("user@example.com")))))
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verifyServices = services => assertTrue(services.authService.authorizeHanded.calls.isEmpty, services.authService.authorize.calls.size == 1),
      ),
      controllerTestCase(
        description = "a handoff that names the session the browser had in the first cell records it on the conversation it starts",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed")),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.handoff.open.succeedsWith(payload(submission = None, superseded = Some(supersededSession)))
            _ <- services.parser.validate.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorizeHanded.succeedsWith(AuthorizeResponse.Initialize(authId))
            _ <- services.router.supersedes.succeedsWith(())
          yield (),
        verifyServices = services => assertTrue(services.router.supersedes.calls == List((authId, supersededSession))),
      ),
      controllerTestCase(
        description = "a handoff that names no session records none",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed")),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.handoff.open.succeedsWith(payload(submission = None, superseded = None))
            _ <- services.parser.validate.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorizeHanded.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verifyServices = services => assertTrue(services.router.supersedes.calls.isEmpty),
      ),
      controllerTestCase(
        description = "a handoff that is not this cell's to open is a 400 and starts nothing",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-2.sealed")),
        expectedStatus = Status.BadRequest,
        setup = _.handoff.open.failsWith(CellHandoff.Refused.Elsewhere),
      ),
      controllerTestCase(
        description = "a handoff whose parameters this cell does not accept is answered like any authorization request that does not parse",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed")),
        expectedStatus = Status.BadRequest,
        setup = services =>
          services.handoff.open.succeedsWith(CellHandoff.Payload(
            params = Map.empty,
            from = "kz-2",
            to = "kz-1",
            expiresAt = 0L,
            submission = None,
            superseded = None,
          )) *>
            services.parser.validate.failsWith(Error.BadRequest),
      ),
      controllerTestCase(
        // The redirect carries the code the way the token endpoint reads it back: as the routed
        // string when it was issued under a cell, with the key no client is expected to strip.
        description = "places a code issued under a cell on the redirect with its routing key",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(
              AuthorizeResponse.Authorized(AuthorizationCode.issue(Some(TestEnvConfig.cellId), Array.fill(32)(1.toByte)), None),
            )
          yield (),
        verify = resp =>
          ZIO.succeed(assertTrue(
            resp.header(Header.Location).exists(_.url.encode.contains(s"code=v1.${TestEnvConfig.cellId}.")),
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
              count = None,
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
        description = "a request that does not parse records authorize.request failure invalid_request",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.BadRequest,
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
        description = "a request that starts a conversation does so whatever the state of the audit queue: the conversation cookie is set",
        request = Request.get(URL.root / "authorize"),
        expectedStatus = Status.SeeOther,
        audit = CapturingAuditEvents.shared(unavailable = true),
        setup = services =>
          for
            _ <- services.parser.parse.succeedsWith(baseRequest)
            _ <- services.configService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
            _ <- services.authService.authorize.succeedsWith(AuthorizeResponse.Initialize(authId))
          yield (),
        verify = resp => ZIO.succeed(assertTrue(resp.header(Header.SetCookie).nonEmpty)),
      ),
    ),
    // versolauth/versola#1048: a submission another cell took and sent here with a 307.
    suite("POST /challenge/handoff")(
      handedTestCase(
        description = "refuses a seal that carries no submission, and starts nothing",
        payload = payload(submission = None),
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(services.assertions.recordIfAbsent.calls.isEmpty, services.authService.authorize.calls.isEmpty),
      ),
      handedTestCase(
        description = "refuses a request that carries no conversation cookie",
        payload = payload(submission = Some(submission(cookie = handedCookie))),
        cookie = None,
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(services.assertions.recordIfAbsent.calls.isEmpty, services.authService.authorize.calls.isEmpty),
      ),
      handedTestCase(
        description = "refuses a request whose conversation cookie is not the one the seal was made with",
        payload = payload(submission = Some(submission(cookie = handedCookie))),
        cookie = Some("another-browsers-cookie"),
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(services.assertions.recordIfAbsent.calls.isEmpty, services.authService.authorize.calls.isEmpty),
      ),
      handedTestCase(
        description = "refuses a seal that was presented before, and does not authorize the request",
        payload = payload(submission = Some(submission(cookie = handedCookie))),
        taken = false,
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls == List(("cell handoff", "sub-1", Instant.ofEpochSecond(1000L))),
            services.authService.authorize.calls.isEmpty,
            services.router.submitHanded.calls.isEmpty,
          ),
      ),
      handedTestCase(
        description = "refuses a seal that is not this cell's to open",
        payload = payload(submission = Some(submission(cookie = handedCookie))),
        opened = false,
        expectedStatus = Status.BadRequest,
        verifyServices = services => assertTrue(services.assertions.recordIfAbsent.calls.isEmpty),
      ),
      handedTestCase(
        description = "starts the request and has the conversation it starts check the login and password, whose CSRF token is not looked at",
        payload = payload(submission = Some(submission(cookie = handedCookie))),
        expectedStatus = Status.SeeOther,
        verify = resp => ZIO.succeed(assertTrue(resp.headers(Header.SetCookie).exists(_.value.name == ConversationCookie.name))),
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls == List(("cell handoff", "sub-1", Instant.ofEpochSecond(1000L))),
            services.authService.authorize.calls.size == 1,
            services.router.submitHanded.calls == List(
              (authId, LoginPasswordSubmission(Login("user"), Password("s3cret"), "first-cells-csrf"), None, None, None),
            ),
          ),
      ),
      handedTestCase(
        description = "records the session the browser had in the first cell on the conversation it starts, before the submission is checked",
        payload = payload(submission = Some(submission(cookie = handedCookie)), superseded = Some(supersededSession)),
        trace = handedTrace,
        expectedStatus = Status.SeeOther,
        verifyServices = services =>
          assertTrue(
            services.router.supersedes.calls == List((authId, supersededSession)),
            services.router.submitHanded.calls.size == 1,
            handedTrace.toArray.toList == List("supersedes", "submitHanded"),
          ),
      ),
      handedTestCase(
        description = "records no session when the seal names none",
        payload = payload(submission = Some(submission(cookie = handedCookie)), superseded = None),
        expectedStatus = Status.SeeOther,
        verifyServices = services => assertTrue(services.router.supersedes.calls.isEmpty, services.router.submitHanded.calls.size == 1),
      ),
      handedTestCase(
        description = "records no session when the seal is refused, so a replayed seal ends nothing",
        payload = payload(submission = Some(submission(cookie = handedCookie)), superseded = Some(supersededSession)),
        taken = false,
        expectedStatus = Status.BadRequest,
        verifyServices = services => assertTrue(services.router.supersedes.calls.isEmpty),
      ),
      handedTestCase(
        description = "records no session when the cookie is not the one the seal was made with",
        payload = payload(submission = Some(submission(cookie = handedCookie)), superseded = Some(supersededSession)),
        cookie = Some("another-browsers-cookie"),
        expectedStatus = Status.BadRequest,
        verifyServices = services => assertTrue(services.router.supersedes.calls.isEmpty),
      ),
      handedTestCase(
        description = "has the conversation it starts check a passkey with the request the seal carries",
        payload = payload(submission = Some(submission(cookie = handedCookie, passkeyRequest = Some("""{"challenge":"c"}""")))),
        form = Form.fromStrings("response" -> """{"id":"cred-1"}""", "csrf" -> "first-cells-csrf"),
        expectedStatus = Status.SeeOther,
        verify = resp => ZIO.succeed(assertTrue(resp.headers(Header.SetCookie).exists(_.value.name == ConversationCookie.name))),
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls == List(("cell handoff", "sub-1", Instant.ofEpochSecond(1000L))),
            services.router.submitHanded.calls == List(
              (authId, PasskeyAssertionSubmission("""{"id":"cred-1"}""", "first-cells-csrf"), Some("""{"challenge":"c"}"""), None, None),
            ),
          ),
      ),
    ),
    // versolauth/versola#1052: a verified registration another cell handed to this one, which takes users.
    suite("GET /challenge/registration")(
      registeredTestCase(
        description = "starts the request here and has the conversation it starts take the registration, with the cookie of that conversation",
        expectedStatus = Status.SeeOther,
        verify = resp => ZIO.succeed(assertTrue(resp.headers(Header.SetCookie).exists(_.value.name == ConversationCookie.name))),
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls == List(("cell registration", "reg-1", Instant.ofEpochSecond(1000L))),
            services.authService.authorizeHanded.calls == List(baseRequest),
            services.authService.authorize.calls.isEmpty,
            services.router.submitRegistered.calls == List((authId, handedRegistration, None, None)),
          ),
      ),
      registeredTestCase(
        description = "records the session the browser had in the first cell before the registration is taken",
        opened = Right(registrationPayload(superseded = Some(supersededSession))),
        trace = ConcurrentLinkedQueue[String](),
        expectedStatus = Status.SeeOther,
        verifyServices = services => assertTrue(services.router.supersedes.calls == List((authId, supersededSession))),
      ),
      registeredTestCase(
        description = "refuses a seal that is not this cell's to open, whatever the refusal, and starts nothing",
        opened = Left(CellHandoff.Refused.Elsewhere),
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls.isEmpty,
            services.authService.authorizeHanded.calls.isEmpty,
            services.router.submitRegistered.calls.isEmpty,
          ),
      ),
      registeredTestCase(
        description = "refuses a seal made for another use, expired or forged",
        opened = Left(CellHandoff.Refused.Purpose),
        expectedStatus = Status.BadRequest,
        verifyServices = services => assertTrue(services.router.submitRegistered.calls.isEmpty),
      ),
      registeredTestCase(
        description = "refuses a request that carries no conversation cookie",
        cookie = None,
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls.isEmpty,
            services.authService.authorizeHanded.calls.isEmpty,
            services.router.submitRegistered.calls.isEmpty,
          ),
      ),
      registeredTestCase(
        description = "refuses a request whose conversation cookie is not the one the seal was made with, and does not take the seal",
        cookie = Some("another-browsers-cookie"),
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls.isEmpty,
            services.authService.authorizeHanded.calls.isEmpty,
            services.router.submitRegistered.calls.isEmpty,
            services.router.supersedes.calls.isEmpty,
          ),
      ),
      registeredTestCase(
        description = "refuses a seal that was presented before, and registers nobody",
        taken = false,
        opened = Right(registrationPayload(superseded = Some(supersededSession))),
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls == List(("cell registration", "reg-1", Instant.ofEpochSecond(1000L))),
            services.authService.authorizeHanded.calls.isEmpty,
            services.router.submitRegistered.calls.isEmpty,
            services.router.supersedes.calls.isEmpty,
          ),
      ),
      registeredTestCase(
        description = "refuses a seal that carries no credential, or two",
        opened = Right(registrationPayload(phone = None)),
        expectedStatus = Status.BadRequest,
        verifyServices = services => assertTrue(services.router.submitRegistered.calls.isEmpty),
      ),
      registeredTestCase(
        description = "answers a request this cell does not accept like any authorization request that does not parse, and registers nobody",
        validated = Left(Error.BadRequest),
        expectedStatus = Status.BadRequest,
        verifyServices = services =>
          assertTrue(
            services.assertions.recordIfAbsent.calls.isEmpty,
            services.router.submitRegistered.calls.isEmpty,
          ),
      ),
    ),
    suite("GET /challenge/handoff with a submission seal")(
      controllerTestCase(
        description = "is refused, since a GET has no submission to go with it",
        request = Request.get((URL.root / "challenge" / "handoff").addQueryParam(CellHandoff.Parameter, "v1.kz-1.sealed")),
        expectedStatus = Status.BadRequest,
        setup = services =>
          services.handoff.open.succeedsWith(payload(submission = Some(submission(cookie = handedCookie)))),
        verifyAudit = _ => assertCompletes,
      ),
    ),
  )
