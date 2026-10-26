package versola.central.configuration.delivery

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.{BadRequest, Controller}
import versola.util.{Base64Url, Secret, SecurityService}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.{Schema, derived}
import zio.telemetry.opentelemetry.tracing.Tracing

object DeliverySettingsController extends Controller:
  type Env = Tracing & DeliverySettingsService & CentralConfig & SecurityService & ResourceService & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getDeliverySettingsEndpoint,
    syncDeliverySettingsEndpoint,
    upsertDeliverySettingsEndpoint,
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
        _ <- service.upsertSettings(body).mapError {
          case error: DeliverySettingsService.ValidationError => BadRequest(error.message)
          case other => other
        }
      yield Response.status(Status.NoContent)
    }

  /** The admin surface's view: each block without its password, and whether one is on record. */
  case class Settings(
      smtp: Option[SmtpDeliverySettings],
      smtpPasswordSet: Boolean,
      otpProvider: Option[OtpProviderDeliverySettings],
      otpProviderPasswordSet: Boolean,
  ) derives Schema, JsonCodec

  object Settings:
    def of(record: Option[DeliverySettingsRecord]): Settings =
      Settings(
        smtp = record.flatMap(_.smtp),
        smtpPasswordSet = record.exists(_.smtpPassword.isDefined),
        otpProvider = record.flatMap(_.otpProvider),
        otpProviderPasswordSet = record.exists(_.otpProviderPassword.isDefined),
      )

  /** `stored` is false until the first save, which is when auth stops reading the `smtp` and
    * `otp-provider` blocks of its own `auth.conf`: a record that was saved with both blocks
    * empty still counts as stored.
    */
  case class SyncResponse(
      stored: Boolean,
      smtp: Option[SmtpDeliverySettings],
      smtpPassword: Option[String],
      otpProvider: Option[OtpProviderDeliverySettings],
      otpProviderPassword: Option[String],
  ) derives Schema, JsonCodec
