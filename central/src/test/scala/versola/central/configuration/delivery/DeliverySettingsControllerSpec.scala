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

  private val path = URL.empty / "configuration" / "delivery-settings"

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

  // Main code deliberately has no encoder for the update, which carries plaintext passwords.
  private given JsonEncoder[DeliverySettingsService.Update] = DeriveJsonEncoder.gen

  private val update = DeliverySettingsService.Update(
    smtp = Some(smtp),
    smtpPassword = Some(smtpPassword),
    otpProvider = Some(otpProvider),
    otpProviderPassword = None,
  )

  private def token(key: Array[Byte]): String = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          claims = JWT.Claims(issuer = "a", subject = "b", audience = List("c"), custom = Json.Obj()),
          ttl = 1.minute,
          signature = JWT.Signature.Symmetric(SecretKeySpec(key, "AES")),
        ),
      )
      .getOrThrowFiberFailure()
  }

  /** Signed under `TestCentralConfig.config.secretKey`, the way auth signs its own. */
  private val syncToken = token(Array.fill(32)(7.toByte))

  private val foreignToken = token(Array.fill(32)(8.toByte))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val securityLayer: ULayer[SecurityService] = ZLayer.make[SecurityService](SecureRandom.live, SecurityService.live)

  private def decode[A: JsonDecoder](response: Response): Task[A] =
    response.body.asString.flatMap(body => ZIO.fromEither(body.fromJson[A]).mapError(error => RuntimeException(s"$error: $body")))

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[DeliverySettingsService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[DeliverySettingsService], SecurityService) => Task[TestResult] = (_, _, _) => ZIO.succeed(assertTrue(true)),
      // Without any Authorization header; otherwise a request that carries none is sent with the admin credential.
      anonymous: Boolean = false,
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
          case None if !anonymous => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service, security.get[SecurityService])
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def put(body: String): Request =
    Request(method = Method.PUT, url = path, body = Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))

  private val notBasic = Header.Authorization.Bearer("not-basic")

  def spec = suite("DeliverySettingsController")(
    controllerTestCase(
      description = "GET returns each block and whether its password is set, never the password",
      request = Request.get(path),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (response, svc, _) =>
        for
          raw <- response.body.asString
          payload <- decode[DeliverySettingsController.Settings](response)
        yield assertTrue(
          svc.getSettings.calls.length == 1,
          payload == DeliverySettingsController.Settings(
            smtp = Some(smtp),
            hasSmtpPassword = true,
            otpProvider = Some(otpProvider),
            hasOtpProviderPassword = false,
          ),
          !raw.contains(smtpPassword),
        ),
    ),
    controllerTestCase(
      description = "GET answers empty blocks while nothing is stored",
      request = Request.get(path),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(None),
      verify = (response, _, _) =>
        for payload <- decode[DeliverySettingsController.Settings](response)
        yield assertTrue(
          payload == DeliverySettingsController.Settings(smtp = None, hasSmtpPassword = false, otpProvider = None, hasOtpProviderPassword = false),
        ),
    ),
    controllerTestCase(
      description = "GET returns 401 without credentials",
      request = Request.get(path).addHeader(notBasic),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT stores the update and returns 204",
      request = put(update.toJson),
      expectedStatus = Status.NoContent,
      setup = svc => svc.upsertSettings.succeedsWith(Right(())),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.upsertSettings.calls == List(update))),
    ),
    controllerTestCase(
      description = "PUT without a password field, or with null, keeps it absent so the stored one is kept",
      request =
        put("""{"smtp":null,"smtpPassword":null,"otpProvider":{"method":"POST","url":"https://sms.example.com/send","body":{},"enabled":true}}"""),
      expectedStatus = Status.NoContent,
      setup = svc => svc.upsertSettings.succeedsWith(Right(())),
      verify = (_, svc, _) =>
        ZIO.succeed(
          assertTrue(
            svc.upsertSettings.calls == List(
              DeliverySettingsService.Update(
                smtp = None,
                smtpPassword = None,
                otpProvider = Some(OtpProviderDeliverySettings(
                  method = "POST",
                  url = "https://sms.example.com/send",
                  username = None,
                  body = Map.empty,
                  enabled = true,
                )),
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
      setup = svc => svc.upsertSettings.succeedsWith(Left(DeliverySettingsService.ValidationError("smtp.from must be an email address"))),
      verify = (response, _, _) =>
        for text <- response.body.asString
        yield assertTrue(text == "smtp.from must be an email address"),
    ),
    controllerTestCase(
      description = "PUT answers 400 for a body that is not an update",
      request = put("""{"smtp":{"host":"x"}}"""),
      expectedStatus = Status.BadRequest,
      setup = svc => svc.upsertSettings.succeedsWith(Right(())),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT returns 401 without credentials",
      request = put(update.toJson).addHeader(notBasic),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.upsertSettings.succeedsWith(Right(())),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "DELETE removes the record and returns 204",
      request = Request.delete(path),
      expectedStatus = Status.NoContent,
      setup = svc => (() => svc.deleteSettings()).succeedsWith(()),
      verify = (_, svc, _) => ZIO.succeed(assertTrue((() => svc.deleteSettings()).calls.length == 1)),
    ),
    controllerTestCase(
      description = "DELETE returns 401 without credentials",
      request = Request.delete(path).addHeader(notBasic),
      expectedStatus = Status.Unauthorized,
      setup = svc => (() => svc.deleteSettings()).succeedsWith(()),
      verify = (_, svc, _) => ZIO.succeed(assertTrue((() => svc.deleteSettings()).calls.isEmpty)),
    ),
    controllerTestCase(
      description = "GET sync serves auth the stored record with the passwords encrypted under the transport secret",
      request = Request.get(path / "sync").addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (response, _, security) =>
        for
          raw <- response.body.asString
          payload <- decode[DeliverySettingsController.SyncResponse](response)
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
      request = Request.get(path / "sync").addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = svc => svc.getSettings.succeedsWith(None),
      verify = (response, _, _) =>
        for payload <- decode[DeliverySettingsController.SyncResponse](response)
        yield assertTrue(
          payload == DeliverySettingsController.SyncResponse(
            stored = false,
            smtp = None,
            smtpPassword = None,
            otpProvider = None,
            otpProviderPassword = None,
          ),
        ),
    ),
    controllerTestCase(
      description = "GET sync returns 401 for a token signed under another key",
      request = Request.get(path / "sync").addHeader(Header.Authorization.Bearer(foreignToken)),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "GET sync returns 401 for the admin Basic credential",
      request = Request.get(path / "sync"),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "GET sync returns 401 without any credential",
      request = Request.get(path / "sync"),
      expectedStatus = Status.Unauthorized,
      setup = svc => svc.getSettings.succeedsWith(Some(stored)),
      verify = (_, svc, _) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
      anonymous = true,
    ),
  )
