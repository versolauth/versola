package versola.central.configuration.delivery

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.{BadRequest, Controller, Observability}
import versola.util.{Base64Url, Secret, SecurityService}
import zio.http.{Header, Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.{Duration, ZIO}

object DeliverySettingsController extends Controller:
  type Env = Tracing & DeliverySettingsService & DeliveryTestService & CentralConfig & SecurityService & ResourceService & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getDeliverySettingsEndpoint,
    syncDeliverySettingsEndpoint,
    upsertDeliverySettingsEndpoint,
    deleteDeliverySettingsEndpoint,
    testDeliveryEndpoint,
  )

  val getDeliverySettingsEndpoint =
    Method.GET / "configuration" / "delivery-settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[DeliverySettingsService]
        settings <- service.getSettings
      yield Response.json(Settings.of(settings).toJson)
    }

  /** For auth. Separate from the admin surface because this is the one answer that carries the
    * passwords: encrypted under the shared transport secret and base64url encoded, the way client
    * secrets already travel on this channel.
    */
  val syncDeliverySettingsEndpoint =
    Method.GET / "configuration" / "delivery-settings" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[DeliverySettingsService]
        config <- ZIO.service[CentralConfig]
        securityService <- ZIO.service[SecurityService]
        settings <- service.getSettings
        transportEncrypt = (secret: Secret) => securityService.encryptAes256(secret, config.secretKey).map(Base64Url.encode)
        smtpPassword <- ZIO.foreach(settings.flatMap(_.smtpPassword))(transportEncrypt)
        otpProviderPassword <- ZIO.foreach(settings.flatMap(_.otpProviderPassword))(transportEncrypt)
      yield Response.json(
        SyncResponse(
          stored = settings.isDefined,
          smtp = settings.flatMap(_.smtp),
          smtpPassword = smtpPassword,
          otpProvider = settings.flatMap(_.otpProvider),
          otpProviderPassword = otpProviderPassword,
        ).toJson,
      )
    }

  val upsertDeliverySettingsEndpoint =
    Method.PUT / "configuration" / "delivery-settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[DeliverySettingsService]
        body <- request.bodyAs[DeliverySettingsService.Update]
          .tapError(_ => Observability.setError("invalid_delivery_settings", Some("the body is not a delivery settings update")))
        _ <- service.upsertSettings(body).flatMap {
          case Left(error) => Observability.setError("invalid_delivery_settings", Some(error.message)) *> ZIO.fail(BadRequest(error.message))
          case Right(()) => ZIO.unit
        }
      yield Response.status(Status.NoContent)
    }

  /** Back to nothing stored: auth reads the blocks of its own `auth.conf` again, as before the first save. */
  val deleteDeliverySettingsEndpoint =
    Method.DELETE / "configuration" / "delivery-settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[DeliverySettingsService]
        _ <- service.deleteSettings()
      yield Response.status(Status.NoContent)
    }

  /** Sends one message through the block the administrator is editing, saved or not. A request that cannot be tried is a
    * `400` naming the field; an attempt that fails is a `200` that says so, because the question was whether delivery works.
    */
  val testDeliveryEndpoint =
    Method.POST / "configuration" / "delivery-settings" / "test" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[DeliveryTestService]
        body <- request.bodyAs[DeliveryTestService.Request]
          .tapError(_ => Observability.setError("invalid_delivery_test", Some("the body is not a delivery test request")))
        result <- service.send(body)
        response <- result match
          case Left(refused) if refused.rateLimited =>
            Observability.setError("rate_limit_exceeded", Some(refused.message)).as(
              Response.status(Status.TooManyRequests).addHeader(Header.RetryAfter.ByDuration(Duration.fromSeconds(60))),
            )
          case Left(refused) => Observability.setError("invalid_delivery_test", Some(refused.message)) *> ZIO.fail(BadRequest(refused.message))
          case Right(outcome) => ZIO.succeed(Response.json(TestResult.of(outcome).toJson))
      yield response
    }

  /** The answer of a test: `delivered`, or why not as a code from a closed set and a sentence that repeats nothing the
    * other side said. */
  case class TestResult(delivered: Boolean, code: Option[String], message: Option[String]) derives JsonCodec

  object TestResult:
    def of(outcome: DeliveryTestService.Outcome): TestResult = outcome match
      case DeliveryTestService.Outcome.Delivered => TestResult(delivered = true, code = None, message = None)
      case DeliveryTestService.Outcome.Failed(code, message) => TestResult(delivered = false, code = Some(code), message = Some(message))

  /** The admin surface's view: each block without its password, and whether one is on record. */
  case class Settings(
      smtp: Option[SmtpDeliverySettings],
      hasSmtpPassword: Boolean,
      otpProvider: Option[OtpProviderDeliverySettings],
      hasOtpProviderPassword: Boolean,
  ) derives JsonCodec

  object Settings:
    def of(record: Option[DeliverySettingsRecord]): Settings =
      Settings(
        smtp = record.flatMap(_.smtp),
        hasSmtpPassword = record.exists(_.smtpPassword.isDefined),
        otpProvider = record.flatMap(_.otpProvider),
        hasOtpProviderPassword = record.exists(_.otpProviderPassword.isDefined),
      )

  /** `stored` is false until the first save and again after a delete. Auth decides each channel on its own: a block
    * saved here is used (or, switched off, sends nothing), and a block that is not saved here is read from its own
    * `auth.conf`, whether or not the other one is. A record saved with both blocks empty still counts as stored.
    */
  case class SyncResponse(
      stored: Boolean,
      smtp: Option[SmtpDeliverySettings],
      smtpPassword: Option[String],
      otpProvider: Option[OtpProviderDeliverySettings],
      otpProviderPassword: Option[String],
  ) derives JsonCodec
