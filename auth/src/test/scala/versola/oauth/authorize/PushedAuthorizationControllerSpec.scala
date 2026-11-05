package versola.oauth.authorize

import org.scalamock.stubs.Stub
import versola.audit.{AuthActors, AuthAudit, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents, ClientAuthFailures, ImmediateClientAuthFailures}
import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.{PushedAuthorizationError, PushedAuthorizationResponse}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.clientauth.{ClientAssertionService, ClientAuthentication}
import versola.oauth.model.{RequestUri, RequestUriReference}
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.{NoopTracing, Observability}
import versola.util.{Base64, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

object PushedAuthorizationControllerSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val clientSecret = versola.util.Secret(Array.fill(32)(4.toByte))
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val config = TestEnvConfig.coreConfig

  private val pushedResponse = PushedAuthorizationResponse(
    requestUri = RequestUri(RequestUriReference(Array.fill(32)(6.toByte))),
    expiresIn = config.parOrDefault.requestUriTtl.toSeconds,
  )

  private def validForm(extra: (String, String)*): Form =
    Form.fromStrings(
      Seq(
        "client_id" -> (clientId: String),
        "response_type" -> "code",
        "redirect_uri" -> redirectUri.encode,
        "scope" -> "openid",
        "code_challenge" -> "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        "code_challenge_method" -> "S256",
      ) ++ extra*,
    )

  private def matchesCredentials(credentials: ClientCredentials): Boolean =
    credentials match
      case ClientIdWithSecret(id, secret) =>
        id == clientId && secret.exists(java.util.Arrays.equals(_, clientSecret))

  /** Chunked, so the request carries no Content-Length the size check could rely on. */
  private val oversizedStream =
    zio.stream.ZStream.fromChunk(Chunk.fill(config.parOrDefault.maxRequestSize * 4)('a'.toByte))

  private def parRequest(form: Form, withAuth: Boolean = true): Request =
    val request = Request.post(URL.empty / "par", Body.fromURLEncodedForm(form))
    if withAuth then request.addHeader(Header.Authorization.Basic(clientId, Base64.urlEncode(clientSecret)))
    else request

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[PushedAuthorizationService] => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      configureClient: Stub[OAuthConfigurationService] => UIO[Unit] = _ => ZIO.unit,
      verifyService: Stub[PushedAuthorizationService] => UIO[TestResult] =
        (_: Stub[PushedAuthorizationService]) => ZIO.succeed(assertTrue(true)),
      verifyAudit: List[AuditDraft] => TestResult = _ => assertCompletes,
      tiers: AuthAudit.Tiers = CapturingAuditEvents.tiers,
      audit: CapturingAuditEvents = CapturingAuditEvents.shared(),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[PushedAuthorizationService]
        clientService = stub[OAuthConfigurationService]
        // The controller looks the client up only to decide whether reading a client
        // certificate could matter to it; an unknown client never needs one.
        _ = clientService.find.returnsWith(ZIO.none)
        clientAuthentication = ClientAuthentication.Impl(clientService, stub[ClientAssertionService], config, ClientAuthFailures.noop)
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            PushedAuthorizationController.routes
              .provideEnvironment(
                ZEnvironment(service) ++ ZEnvironment(clientAuthentication) ++ ZEnvironment(config) ++ ZEnvironment[AuditEvents](audit) ++
                  ZEnvironment(tiers) ++ ZEnvironment[ClientAuthFailures](ImmediateClientAuthFailures(audit)) ++ tracing,
              ),
          ),
        )
        _ <- setup(service)
        _ <- configureClient(clientService)
        response <- client.batched(request)
        verifyResult <- verify(response)
        verifyServiceResult <- verifyService(service)
        drafts <- audit.recorded
      yield assertTrue(response.status == expectedStatus) && verifyResult && verifyServiceResult && verifyAudit(drafts)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("PushedAuthorizationController")(
    suite("POST /par")(
      controllerTestCase(
        description = "returns 201 with the request_uri and its lifetime",
        request = parRequest(validForm()),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verify = response =>
          for
            body <- response.body.asString
            json <- ZIO.fromEither(zio.json.JsonDecoder[Json.Obj].decodeJson(body)).mapError(RuntimeException(_))
          yield assertTrue(
            json.get("request_uri").flatMap(_.asString) == Some(pushedResponse.requestUri),
            json.get("expires_in").flatMap(_.asNumber).map(_.value.longValue) == Some(pushedResponse.expiresIn),
            response.header(Header.CacheControl).isDefined,
          ),
      ),
      controllerTestCase(
        description = "passes the pushed parameters and Basic credentials to the service",
        request = parRequest(validForm("state" -> "test-state")),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyService = service =>
          ZIO.succeed:
            val pushed = service.push.calls
            assertTrue(
              pushed.size == 1,
              pushed.head._1.get("state") == Some(Chunk("test-state")),
              matchesCredentials(pushed.head._2),
            ),
      ),
      controllerTestCase(
        description = "hands every DPoP header to the service, which decides whether they are acceptable",
        request = parRequest(validForm()).addHeader(Header.Custom("DPoP", "proof-1")).addHeader(Header.Custom("DPoP", "proof-2")),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyService = service =>
          ZIO.succeed(assertTrue(service.push.calls.head._5 == List("proof-1", "proof-2"))),
      ),
      controllerTestCase(
        description = "passes no proofs when the request has no DPoP header",
        request = parRequest(validForm()),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyService = service =>
          ZIO.succeed(assertTrue(service.push.calls.head._5 == Nil)),
      ),
      controllerTestCase(
        description = "answers use_dpop_nonce with the nonce in the DPoP-Nonce header",
        request = parRequest(validForm()).addHeader(Header.Custom("DPoP", "proof")),
        expectedStatus = Status.BadRequest,
        setup = _.push.failsWith(PushedAuthorizationError.UseDpopNonce("fresh-nonce")),
        verify = response =>
          response.body.asString.map(body =>
            assertTrue(
              body.contains("use_dpop_nonce"),
              response.rawHeader("DPoP-Nonce") == Some("fresh-nonce"),
            ),
          ),
      ),
      controllerTestCase(
        description = "answers an unacceptable proof with invalid_dpop_proof",
        request = parRequest(validForm()).addHeader(Header.Custom("DPoP", "proof")),
        expectedStatus = Status.BadRequest,
        setup = _.push.failsWith(PushedAuthorizationError.InvalidDpopProof("bad signature")),
        verify = response =>
          response.body.asString.map(body => assertTrue(body.contains("invalid_dpop_proof"))),
      ),
      controllerTestCase(
        description = "authenticates with client_secret_post",
        request = parRequest(
          validForm("client_secret" -> Base64.urlEncode(clientSecret)),
          withAuth = false,
        ),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyService = service =>
          ZIO.succeed:
            assertTrue(matchesCredentials(service.push.calls.head._2)),
      ),
      controllerTestCase(
        description = "renders service errors with the token endpoint error format",
        request = parRequest(validForm()),
        expectedStatus = Status.Unauthorized,
        setup = _.push.failsWith(PushedAuthorizationError.InvalidClient),
        verify = response =>
          response.body.asString.map(body =>
            assertTrue(body.contains("invalid_client"), response.header(Header.CacheControl).isDefined),
          ),
      ),
      controllerTestCase(
        description = "includes a WWW-Authenticate challenge on invalid_client",
        request = parRequest(validForm()),
        expectedStatus = Status.Unauthorized,
        setup = _.push.failsWith(PushedAuthorizationError.InvalidClient),
        verify = response =>
          ZIO.succeed(assertTrue(response.header(Header.WWWAuthenticate).isDefined)),
      ),
      controllerTestCase(
        description = "rejects a body larger than the configured maximum",
        request = parRequest(validForm("state" -> "a".repeat(config.parOrDefault.maxRequestSize + 1))),
        expectedStatus = Status.RequestEntityTooLarge,
        verify = response =>
          response.body.asString.map(body => assertTrue(body.contains("invalid_request"))),
      ),
      controllerTestCase(
        description = "rejects an oversized body that does not declare its length",
        request = Request
          .post(URL.empty / "par", Body.fromStreamChunked(oversizedStream))
          .addHeader(Header.Authorization.Basic(clientId, Base64.urlEncode(clientSecret))),
        expectedStatus = Status.RequestEntityTooLarge,
        verify = response =>
          response.body.asString.map(body => assertTrue(body.contains("invalid_request"))),
      ),
    ),
    suite("non-POST /par")(
      controllerTestCase(
        description = "answers GET with 405",
        request = Request.get(URL.empty / "par"),
        expectedStatus = Status.MethodNotAllowed,
        verify = response =>
          response.body.asString.map(body => assertTrue(body.contains("POST"))),
      ),
      controllerTestCase(
        description = "answers PUT with 405",
        request = Request.put(URL.empty / "par", Body.empty),
        expectedStatus = Status.MethodNotAllowed,
      ),
      controllerTestCase(
        description = "includes an Allow: POST header on 405",
        request = Request.get(URL.empty / "par"),
        expectedStatus = Status.MethodNotAllowed,
        verify = response =>
          ZIO.succeed(assertTrue(response.header(Header.Allow).contains(Header.Allow(NonEmptyChunk(Method.POST))))),
      ),
      controllerTestCase(
        description = "reads the certificate from the header the tenant configured and passes it on",
        request = parRequest(validForm(), withAuth = false)
          .addHeader(Header.Custom("ssl-client-cert", TestEnvConfig.escapedClientCertificatePem)),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        configureClient = client =>
          client.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId))) *>
            client.getMtlsCertificateSource.succeedsWith(Some(TestEnvConfig.nginxCertificateSource)),
        verifyService = service =>
          ZIO.succeed(assertTrue(
            service.push.calls.head._3.map(_.thumbprint).contains(TestEnvConfig.clientCertificateThumbprint),
          )),
      ),
      controllerTestCase(
        description = "ignores the header for a client that does not authenticate by certificate",
        request = parRequest(validForm())
          .addHeader(Header.Custom("ssl-client-cert", TestEnvConfig.escapedClientCertificatePem)),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyService = service =>
          ZIO.succeed(assertTrue(service.push.calls.head._3.isEmpty)),
      ),
      controllerTestCase(
        description = "rejects a header it cannot read as a certificate with invalid_client",
        request = parRequest(validForm(), withAuth = false)
          .addHeader(Header.Custom("ssl-client-cert", "not-a-certificate")),
        expectedStatus = Status.Unauthorized,
        configureClient = client =>
          client.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId))) *>
            client.getMtlsCertificateSource.succeedsWith(Some(TestEnvConfig.nginxCertificateSource)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_client"),
            // The reason names the deployment's proxy, so it stays in the log.
            !body.contains("X.509"),
          ),
        verifyService = service =>
          ZIO.succeed(assertTrue(service.push.calls.isEmpty)),
      ),
    ),
    suite("audit log (par.request)")(
      controllerTestCase(
        description = "a push records par.request success with the scope and whether it was a request object; the client is the actor",
        request = parRequest(validForm()),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyAudit = drafts =>
          assertTrue(
            drafts.map(d => (d.kind, d.outcome, d.reason)) == List((AuthAuditKinds.ParRequest, AuditOutcome.success, None)),
            drafts.head.actor == Some((AuthActors.client, Some(clientId: String))),
            drafts.head.details == AuthAuditDetails.ParRequest(scope = List("openid"), requestObject = false),
          ),
      ),
      controllerTestCase(
        description = "a pushed request object is recorded as one",
        request = parRequest(validForm("request" -> "a.b.c")),
        expectedStatus = Status.Created,
        setup = _.push.succeedsWith(pushedResponse),
        verifyAudit = drafts => assertTrue(drafts.map(_.details).collect { case d: AuthAuditDetails.ParRequest => d.requestObject } == List(true)),
      ),
      controllerTestCase(
        description = "a success is left out when the deployment turned audit.events.par-success off",
        request = parRequest(validForm()),
        expectedStatus = Status.Created,
        tiers = CapturingAuditEvents.tiers.copy(parSuccess = false),
        setup = _.push.succeedsWith(pushedResponse),
        verifyAudit = drafts => assertTrue(drafts.isEmpty),
      ),
      controllerTestCase(
        description = "a refused push is always recorded, with the OAuth error as the reason",
        request = parRequest(validForm()),
        expectedStatus = Status.BadRequest,
        tiers = CapturingAuditEvents.tiers.copy(parSuccess = false),
        setup = _.push.failsWith(PushedAuthorizationError.RequestUriNotAllowed),
        verifyAudit = drafts =>
          assertTrue(drafts.map(d => (d.kind, d.outcome, d.reason)) == List((
            AuthAuditKinds.ParRequest,
            AuditOutcome.failure,
            Some(PushedAuthorizationError.RequestUriNotAllowed.error),
          ))),
      ),
      controllerTestCase(
        description = "invalid_client is not recorded here: it is client.auth_failed, coalesced",
        request = parRequest(validForm()),
        expectedStatus = Status.Unauthorized,
        setup = _.push.failsWith(PushedAuthorizationError.InvalidClient),
        verifyAudit = drafts => assertTrue(drafts.isEmpty),
      ),
      controllerTestCase(
        description = "a push is accepted whatever the state of the audit queue",
        request = parRequest(validForm()),
        expectedStatus = Status.Created,
        audit = CapturingAuditEvents.shared(unavailable = true),
        setup = _.push.succeedsWith(pushedResponse),
      ),
    ),
  )
