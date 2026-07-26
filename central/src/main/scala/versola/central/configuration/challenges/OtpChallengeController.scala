package versola.central.configuration.challenges

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object OtpChallengeController extends Controller:
  type Env = Tracing & OtpChallengeService & ChallengeSettingsService & OAuthClientService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getTemplatesEndpoint,
    syncTemplatesEndpoint,
    upsertTemplateEndpoint,
    deleteTemplateEndpoint,
    getChallengeSettingsEndpoint,
    syncChallengeSettingsEndpoint,
    upsertChallengeSettingsEndpoint,
  )

  val getTemplatesEndpoint =
    Method.GET / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        _         <- authorizeBasic(request)
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
        _       <- authorizeBasic(request)
        service <- ZIO.service[OtpChallengeService]
        body    <- request.body.asJson[UpsertOtpTemplateRequest]
        _       <- service.upsertTemplate(OtpTemplateRecord(body.id, body.tenantId, body.localizations, body.purpose))
      yield Response.status(Status.NoContent)
    }

  val deleteTemplateEndpoint =
    Method.DELETE / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        _        <- authorizeBasic(request)
        service  <- ZIO.service[OtpChallengeService]
        body     <- request.body.asJson[DeleteOtpTemplateRequest]
        _        <- service.deleteTemplate(body.id, body.tenantId)
      yield Response.status(Status.NoContent)
    }

  val getChallengeSettingsEndpoint =
    Method.GET / "configuration" / "challenges" / "challenge-settings" -> handler { (request: Request) =>
      for
        _        <- authorizeBasic(request)
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        service  <- ZIO.service[ChallengeSettingsService]
        settings <- service.getSettings(tenantId)
      yield Response.json(GetChallengeSettingsResponse(settings).toJson)
    }

  val syncChallengeSettingsEndpoint =
    Method.GET / "configuration" / "challenges" / "challenge-settings" / "sync" -> handler { (request: Request) =>
      for
        _        <- authorizeInternal(request)
        service  <- ZIO.service[ChallengeSettingsService]
        settings <- service.getAllSettings
      yield Response.json(GetAllChallengeSettingsResponse(settings).toJson)
    }

  val upsertChallengeSettingsEndpoint =
    Method.PUT / "configuration" / "challenges" / "challenge-settings" -> handler { (request: Request) =>
      for
        _        <- authorizeBasic(request)
        service  <- ZIO.service[ChallengeSettingsService]
        body     <- request.body.asJson[UpsertChallengeSettingsRequest]
        existing <- service.getSettings(body.tenantId)
        _ <- service.upsertSettings(
          ChallengeSettingsRecord(
            body.tenantId,
            body.allowedPrefixes,
            body.submissionLimits,
            body.otpLength,
            body.otpResendAfter,
            body.passkeySettings,
            body.authConversationTtlSeconds.orElse(existing.map(_.authConversationTtlSeconds)).getOrElse(900),
            body.sessionTtlSeconds.orElse(existing.map(_.sessionTtlSeconds)).getOrElse(86400),
            body.sessionIdleTtlSeconds.orElse(existing.flatMap(_.sessionIdleTtlSeconds)),
            body.ipHeader,
            body.acrVocabulary.orElse(existing.flatMap(_.acrVocabulary)),
          ),
        )
      yield Response.status(Status.NoContent)
    }