package versola.central.configuration.challenges

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object OtpChallengeController extends Controller:
  type Env = Tracing & OtpChallengeService & PhoneChallengeService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getTemplatesEndpoint,
    syncTemplatesEndpoint,
    upsertTemplateEndpoint,
    deleteTemplateEndpoint,
    getPhoneSettingsEndpoint,
    syncPhoneSettingsEndpoint,
    upsertPhoneSettingsEndpoint,
  )

  val getTemplatesEndpoint =
    Method.GET / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        tenantId  <- request.url.queryZIO[TenantId]("tenantId")
        service   <- ZIO.service[OtpChallengeService]
        templates <- service.getTemplates(tenantId)
      yield Response.json(GetOtpTemplatesResponse(templates).toJson)
    }

  val syncTemplatesEndpoint =
    Method.GET / "configuration" / "challenges" / "otp-templates" / "sync" -> handler { (request: Request) =>
      for
        _         <- authorizeInternal(request)
        service   <- ZIO.service[OtpChallengeService]
        templates <- service.getSyncTemplates
      yield Response.json(GetOtpTemplatesResponse(templates).toJson)
    }

  val upsertTemplateEndpoint =
    Method.PUT / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OtpChallengeService]
        body    <- request.body.asJson[UpsertOtpTemplateRequest]
        _       <- service.upsertTemplate(OtpTemplateRecord(body.id, body.tenantId, body.localizations))
      yield Response.status(Status.NoContent)
    }

  val deleteTemplateEndpoint =
    Method.DELETE / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        service  <- ZIO.service[OtpChallengeService]
        body     <- request.body.asJson[DeleteOtpTemplateRequest]
        _        <- service.deleteTemplate(body.id, body.tenantId)
      yield Response.status(Status.NoContent)
    }

  val getPhoneSettingsEndpoint =
    Method.GET / "configuration" / "challenges" / "phone-settings" -> handler { (request: Request) =>
      for
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        service  <- ZIO.service[PhoneChallengeService]
        settings <- service.getSettings(tenantId)
      yield Response.json(GetPhoneSettingsResponse(settings).toJson)
    }

  val syncPhoneSettingsEndpoint =
    Method.GET / "configuration" / "challenges" / "phone-settings" / "sync" -> handler { (request: Request) =>
      for
        _        <- authorizeInternal(request)
        service  <- ZIO.service[PhoneChallengeService]
        settings <- service.getAllSettings
      yield Response.json(GetAllPhoneSettingsResponse(settings).toJson)
    }

  val upsertPhoneSettingsEndpoint =
    Method.PUT / "configuration" / "challenges" / "phone-settings" -> handler { (request: Request) =>
      for
        service <- ZIO.service[PhoneChallengeService]
        body    <- request.body.asJson[UpsertPhoneSettingsRequest]
        _       <- service.upsertSettings(PhoneSettingsRecord(body.tenantId, body.allowedPrefixes, body.passwordRegex))
      yield Response.status(Status.NoContent)
    }
