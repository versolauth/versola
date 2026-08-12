package versola.oauth.authorize

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.{PushedAuthorizationError, PushedAuthorizationResponse}
import versola.oauth.client.model.*
import versola.oauth.model.{RequestUri, RequestUriReference}
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
      ) ++ extra*
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
      verifyService: Stub[PushedAuthorizationService] => UIO[TestResult] =
        (_: Stub[PushedAuthorizationService]) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[PushedAuthorizationService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            PushedAuthorizationController.routes
              .provideEnvironment(ZEnvironment(service) ++ ZEnvironment(config) ++ tracing)
          )
        )
        _ <- setup(service)
        response <- client.batched(request)
        verifyResult <- verify(response)
        verifyServiceResult <- verifyService(service)
      yield assertTrue(response.status == expectedStatus) && verifyResult && verifyServiceResult
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
    ),
  )
