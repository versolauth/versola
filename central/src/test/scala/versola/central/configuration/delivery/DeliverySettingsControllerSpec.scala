package versola.central.configuration.delivery

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import versola.util.{Base64, JWT, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

object DeliverySettingsControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config

  private val smtp = SmtpDeliverySettings(
    host = "smtp.example.com",
    port = 587,
    username = "mailer",
    from = "noreply@example.com",
    subject = "Your code",
    startTls = true,
    enabled = true,
  )

  private val otpProvider = OtpProviderDeliverySettings(
    method = "POST",
    url = "https://sms.example.com/send",
    username = None,
    body = Map("phones" -> "{{phone}}", "mes" -> "{{message}}"),
    enabled = true,
  )

  private val smtpPassword = "smtp-s3cret"

  /** What the service serves: the passwords already decrypted. */
  private val stored = DeliverySettingsRecord(
    smtp = Some(smtp),
    smtpPassword = Some(Secret.fromString(smtpPassword)),
    otpProvider = Some(otpProvider),
    otpProviderPassword = None,
  )

  private val update = DeliverySettingsService.Update(
    smtp = Some(smtp),
    smtpPassword = Some(smtpPassword),
    otpProvider = Some(otpProvider),
    otpProviderPassword = None,
  )

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(SecretKeySpec(Array.fill(32)(7.toByte), "AES")),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val securityLayer: ULayer[SecurityService] = ZLayer.make[SecurityService](SecureRandom.live, SecurityService.live)

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[DeliverySettingsService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[DeliverySettingsService], SecurityService) => Task[TestResult] = (_, _, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[DeliverySettingsService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            DeliverySettingsController.routes.provideEnvironment(
              ZEnvironment[DeliverySettingsService](service) ++
                tracing ++ security ++ ZEnvironment[CentralConfig](config) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService),
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service, security.get[SecurityService])
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def put(body: String): Request =
    Request(
      method = Method.PUT,
      url = URL.empty / "configuration" / "delivery-settings",
      body = Body.fromString(body),
    ).addHeader(Header.ContentType(MediaType.application.json))

  def spec = suite("DeliverySettingsController")(
    controllerTestCase(
      description = "GET returns each block and whether its password is set, never the password",
      request = Request.get(URL.empty / "configuration" / "delivery-settings"),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (response, svc, _) =>
        for
          raw <- response.body.asString
          payload <- response.body.asJson[DeliverySettingsController.Settings]
        yield assertTrue(
          svc.getSettings.calls.length == 1,
          payload == DeliverySettingsController.Settings(
            smtp = Some(smtp),
            smtpPasswordSet = true,
            otpProvider = Some(otpProvider),
            otpProviderPasswordSet = false,
          ),
          !raw.contains(smtpPassword),
        ),
    ),
    controllerTestCase(
      description = "GET answers empty blocks while nothing is stored",
      request = Request.get(URL.empty / "configuration" / "delivery-settings"),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(None),
      verify = (response, _, _) =>
        for payload <- response.body.asJson[DeliverySettingsController.Settings]
        yield assertTrue(
          payload == DeliverySettingsController.Settings(smtp = None, smtpPasswordSet = false, otpProvider = None, otpProviderPasswordSet = false),
        ),
    ),
    controllerTestCase(
      description = "GET returns 401 without credentials",
      request = Request.get(URL.empty / "configuration" / "delivery-settings")
        .addHeader(Header.Authorization.Bearer("not-basic")),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT stores the update and returns 204",
      request = put(update.toJson),
      expectedStatus = Status.NoContent,
      setup = svc => svc.upsertSettings.succeedsWith(()),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.upsertSettings.calls == List(update))),
    ),
    controllerTestCase(
      description = "PUT without a password field keeps it absent, so the stored one is kept",
      request = put("""{"smtp":null,"otpProvider":{"method":"POST","url":"https://sms.example.com/send","body":{},"enabled":true}}"""),
      expectedStatus = Status.NoContent,
      setup = svc => svc.upsertSettings.succeedsWith(()),
      verify = (_, svc, _) =>
        ZIO.succeed(
          assertTrue(
            svc.upsertSettings.calls == List(
              DeliverySettingsService.Update(
                smtp = None,
                smtpPassword = None,
                otpProvider = Some(OtpProviderDeliverySettings(method = "POST", url = "https://sms.example.com/send", username = None, body = Map.empty, enabled = true)),
                otpProviderPassword = None,
              ),
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "PUT answers 400 with the reason when the service refuses the update",
      request = put(update.toJson),
      expectedStatus = Status.BadRequest,
      setup = svc => svc.upsertSettings.failsWith(DeliverySettingsService.ValidationError("smtp.from must be an email address")),
      verify = (response, _, _) =>
        for text <- response.body.asString
        yield assertTrue(text == "smtp.from must be an email address"),
    ),
    controllerTestCase(
      description = "PUT answers 400 for a body that is not an update",
      request = put("""{"smtp":{"host":"x"}}"""),
      expectedStatus = Status.BadRequest,
      setup = svc => svc.upsertSettings.succeedsWith(()),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT returns 401 without credentials",
      request = put(update.toJson).addHeader(Header.Authorization.Bearer("not-basic")),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.upsertSettings.succeedsWith(()),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "GET sync serves auth the stored record with the passwords encrypted under the transport secret",
      request = Request
        .get(URL.empty / "configuration" / "delivery-settings" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (response, _, security) =>
        for
          raw <- response.body.asString
          payload <- response.body.asJson[DeliverySettingsController.SyncResponse]
          encrypted <- ZIO.fromOption(payload.smtpPassword).orElseFail(RuntimeException("no smtp password in the sync answer"))
          decrypted <- security.decryptAes256(Base64.urlDecode(encrypted), config.secretKey)
        yield assertTrue(
          payload.stored,
          payload.smtp == Some(smtp),
          payload.otpProvider == Some(otpProvider),
          payload.otpProviderPassword.isEmpty,
          String(decrypted, StandardCharsets.UTF_8) == smtpPassword,
          !raw.contains(smtpPassword),
        ),
    ),
    controllerTestCase(
      description = "GET sync says nothing is stored while there is no record",
      request = Request
        .get(URL.empty / "configuration" / "delivery-settings" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(None),
      verify = (response, _, _) =>
        for payload <- response.body.asJson[DeliverySettingsController.SyncResponse]
        yield assertTrue(
          payload == DeliverySettingsController.SyncResponse(stored = false, smtp = None, smtpPassword = None, otpProvider = None, otpProviderPassword = None),
        ),
    ),
    controllerTestCase(
      description = "GET sync returns 401 without service token",
      request = Request.get(URL.empty / "configuration" / "delivery-settings" / "sync"),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
  )
