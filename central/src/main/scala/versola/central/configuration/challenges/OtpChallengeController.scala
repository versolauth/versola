package versola.central.configuration.challenges

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.Patch.applyTo
import versola.util.http.{BadRequest, Controller}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object OtpChallengeController extends Controller:
  type Env = Tracing & OtpChallengeService & ChallengeSettingsService & ResourceService & CentralConfig & EdgeService

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
        body    <- request.bodyAs[UpsertOtpTemplateRequest]
        _       <- service.upsertTemplate(OtpTemplateRecord(body.id, body.tenantId, body.localizations, body.purpose, body.channel))
      yield Response.status(Status.NoContent)
    }

  val deleteTemplateEndpoint =
    Method.DELETE / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        _        <- authorizeBasic(request)
        service  <- ZIO.service[OtpChallengeService]
        body     <- request.bodyAs[DeleteOtpTemplateRequest]
        _        <- service.deleteTemplate(body.id, body.tenantId, body.purpose, body.channel)
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
        body     <- request.bodyAs[UpsertChallengeSettingsRequest]
        existing <- service.getSettings(body.tenantId)
        mtlsCertificateHeader   = body.mtlsCertificateHeader.applyTo(existing.flatMap(_.mtlsCertificateHeader))
        mtlsCertificateEncoding = body.mtlsCertificateEncoding.applyTo(existing.flatMap(_.mtlsCertificateEncoding))
        // One setting stored in two columns: a header no encoding says how to read, and an
        // encoding that names no header, both leave `auth` with a tenant whose mutual TLS is
        // off while central reports it configured.
        _ <- ZIO.fail(BadRequest("mtlsCertificateHeader and mtlsCertificateEncoding must be set or cleared together"))
          .when(mtlsCertificateHeader.isDefined != mtlsCertificateEncoding.isDefined)
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
            body.userAgentTtlSeconds.orElse(existing.map(_.userAgentTtlSeconds)).getOrElse(15552000),
            body.ipHeader,
            body.acrVocabulary.orElse(existing.flatMap(_.acrVocabulary)),
            body.postLogoutRedirectUris.orElse(existing.map(_.postLogoutRedirectUris)).getOrElse(Nil),
            body.requireDpopNonce.orElse(existing.map(_.requireDpopNonce)).getOrElse(false),
            mtlsCertificateHeader,
            mtlsCertificateEncoding,
            body.signingKeyId.applyTo(existing.flatMap(_.signingKeyId)),
          ),
        )
          // A kid nothing can sign with is the operator naming a key that does not fit, not a
          // fault of this server -- and the message says which of the three reasons it is.
          .mapError {
            case error: ChallengeSettingsService.ValidationError => BadRequest(error.message)
            case other                                          => other
          }
      yield Response.status(Status.NoContent)
    }