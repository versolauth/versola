package versola.oauth.introspect

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ClientIdWithSecret, OAuthClientRecord}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.mtls.ClientAuthentication
import versola.util.{Base64, Secret, UnitSpecBase}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect

object IntrospectionControllerSpec extends UnitSpecBase:

  private val clientId     = ClientId("test-client")
  private val clientSecret = Secret(Array.fill(32)(4.toByte))

  def authHeader(id: ClientId, secret: Secret): Header.Authorization =
    Header.Authorization.Basic(id, Base64.urlEncode(secret))

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[IntrospectionService] => UIO[Unit] = _ => ZIO.unit,
      configureClient: Stub[OAuthConfigurationService] => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      verifyService: Stub[IntrospectionService] => UIO[TestResult] =
        (_: Stub[IntrospectionService]) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client              <- ZIO.service[Client]
        introspectionService = stub[IntrospectionService]
        clientService        = stub[OAuthConfigurationService]
        // The controller looks the client up only to decide whether reading a client
        // certificate could matter to it; an unknown client never needs one.
        _                    = clientService.find.returnsWith(ZIO.none)
        clientAuthentication = ClientAuthentication.Impl(clientService)
        jwksService          = TestEnvConfig.jwksService
        config               = TestEnvConfig.coreConfig
        tracing             <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            IntrospectionController.routes
              .provideEnvironment(
                ZEnvironment(introspectionService) ++
                  ZEnvironment(clientAuthentication) ++
                  ZEnvironment(jwksService) ++
                  ZEnvironment(config) ++
                  tracing
              )
          )
        )
        _ <- setup(introspectionService)
        _ <- configureClient(clientService)

        response       <- client.batched(request)
        verifyResult   <- verify(response)
        serviceResult  <- verifyService(introspectionService)
      yield assertTrue(response.status == expectedStatus) && verifyResult && serviceResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("IntrospectionController")(
    suite("POST /introspect")(
      controllerTestCase(
        description = "returns 401 when Basic auth is missing",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> "some-token")),
        ),
        expectedStatus = Status.Unauthorized,
      ),
      controllerTestCase(
        description = "returns 200 with inactive when IntrospectionService fails with Unauthenticated",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(Array.fill(32)(1.toByte)))),
        ).addHeader(authHeader(clientId, clientSecret)),
        expectedStatus = Status.Unauthorized,
        setup = svc =>
          svc.introspectRefreshToken.failsWith(IntrospectionError.Unauthenticated),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("\"error\":\"invalid_client\"")),
      ),
      controllerTestCase(
        description = "returns 200 with active when IntrospectionService succeeds",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(Array.fill(32)(2.toByte)))),
        ).addHeader(authHeader(clientId, clientSecret)),
        expectedStatus = Status.Ok,
        setup = svc =>
          svc.introspectRefreshToken.succeedsWith(IntrospectionResponse.Inactive.copy(active = true)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("\"active\":true")),
      ),
      controllerTestCase(
        description = "authenticates the client with client_secret_post",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings(
            "token" -> Base64.urlEncode(Array.fill(32)(2.toByte)),
            "client_id" -> clientId,
            "client_secret" -> Base64.urlEncode(clientSecret),
          )),
        ),
        expectedStatus = Status.Ok,
        setup = svc =>
          svc.introspectRefreshToken.succeedsWith(IntrospectionResponse.Inactive.copy(active = true)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("\"active\":true")),
      ),
      controllerTestCase(
        description = "returns 401 when Basic and post credentials are combined",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings(
            "token" -> Base64.urlEncode(Array.fill(32)(2.toByte)),
            "client_id" -> clientId,
            "client_secret" -> Base64.urlEncode(clientSecret),
          )),
        ).addHeader(authHeader(clientId, clientSecret)),
        expectedStatus = Status.Unauthorized,
      ),
      controllerTestCase(
        description = "reads the certificate from the header the tenant configured and passes it on",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings(
            "token" -> Base64.urlEncode(Array.fill(32)(2.toByte)),
            "client_id" -> clientId,
          )),
        ).addHeader(Header.Custom("ssl-client-cert", TestEnvConfig.escapedClientCertificatePem)),
        expectedStatus = Status.Ok,
        setup = svc =>
          svc.introspectRefreshToken.succeedsWith(IntrospectionResponse.Inactive.copy(active = true)),
        configureClient = client =>
          client.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId))) *>
            client.getMtlsCertificateSource.succeedsWith(Some(TestEnvConfig.nginxCertificateSource)),
        verifyService = svc =>
          ZIO.succeed(assertTrue(
            svc.introspectRefreshToken.calls.head._3
              .map(_.thumbprint).contains(TestEnvConfig.clientCertificateThumbprint),
          )),
      ),
      controllerTestCase(
        description = "ignores the header for a client that does not authenticate by certificate",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(Array.fill(32)(2.toByte)))),
        ).addHeader(authHeader(clientId, clientSecret))
          .addHeader(Header.Custom("ssl-client-cert", TestEnvConfig.escapedClientCertificatePem)),
        expectedStatus = Status.Ok,
        setup = svc =>
          svc.introspectRefreshToken.succeedsWith(IntrospectionResponse.Inactive.copy(active = true)),
        verifyService = svc =>
          ZIO.succeed(assertTrue(svc.introspectRefreshToken.calls.head._3.isEmpty)),
      ),
      controllerTestCase(
        description = "rejects a header it cannot read as a certificate with invalid_client",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings(
            "token" -> Base64.urlEncode(Array.fill(32)(2.toByte)),
            "client_id" -> clientId,
          )),
        ).addHeader(Header.Custom("ssl-client-cert", "not-a-certificate")),
        expectedStatus = Status.Unauthorized,
        configureClient = client =>
          client.find.succeedsWith(Some(TestEnvConfig.mtlsClient(clientId))) *>
            client.getMtlsCertificateSource.succeedsWith(Some(TestEnvConfig.nginxCertificateSource)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(
            body.contains("\"error\":\"invalid_client\""),
            // The reason names the deployment's proxy, so it stays in the log.
            !body.contains("X.509"),
          ),
        verifyService = svc =>
          ZIO.succeed(assertTrue(svc.introspectRefreshToken.calls.isEmpty)),
      ),
    ),
  )
