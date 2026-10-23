package versola.central.configuration.challenges

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.Patch.applyTo
import versola.util.http.{BadRequest, Controller}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object OtpChallengeController extends Controller:
  type Env = Tracing & OtpChallengeService & ChallengeSettingsService & ResourceService & CentralConfig & EdgeService & RoleRepository

  def routes: Routes[Env, Throwable] = Routes(
    getTemplatesEndpoint,
    syncTemplatesEndpoint,
    upsertTemplateEndpoint,
    deleteTemplateEndpoint,
    getChallengeSettingsEndpoint,
    syncChallengeSettingsEndpoint,
    upsertChallengeSettingsEndpoint,
    patchChallengeSettingsEndpoint,
  )

  val getTemplatesEndpoint =
    Method.GET / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        service <- ZIO.service[OtpChallengeService]
        templates <- service.getTemplates(tenantId)
      yield Response.json(GetOtpTemplatesResponse(templates).toJson)
    }

  val syncTemplatesEndpoint =
    Method.GET / "configuration" / "challenges" / "otp-templates" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[OtpChallengeService]
        templates <- service.getSyncTemplates
      yield Response.json(GetOtpTemplatesResponse(templates).toJson)
    }

  val upsertTemplateEndpoint =
    Method.PUT / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OtpChallengeService]
        body <- request.bodyAs[UpsertOtpTemplateRequest]
        _ <- service.upsertTemplate(OtpTemplateRecord(body.id, body.tenantId, body.localizations, body.purpose, body.channel))
      yield Response.status(Status.NoContent)
    }

  val deleteTemplateEndpoint =
    Method.DELETE / "configuration" / "challenges" / "otp-templates" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OtpChallengeService]
        body <- request.bodyAs[DeleteOtpTemplateRequest]
        _ <- service.deleteTemplate(body.id, body.tenantId, body.purpose, body.channel)
      yield Response.status(Status.NoContent)
    }

  val getChallengeSettingsEndpoint =
    Method.GET / "configuration" / "challenges" / "challenge-settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        service <- ZIO.service[ChallengeSettingsService]
        settings <- service.getSettings(tenantId)
      yield Response.json(GetChallengeSettingsResponse(settings).toJson)
    }

  val syncChallengeSettingsEndpoint =
    Method.GET / "configuration" / "challenges" / "challenge-settings" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[ChallengeSettingsService]
        settings <- service.getAllSettings
      yield Response.json(GetAllChallengeSettingsResponse(settings).toJson)
    }

  val upsertChallengeSettingsEndpoint =
    Method.PUT / "configuration" / "challenges" / "challenge-settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ChallengeSettingsService]
        body <- request.bodyAs[UpsertChallengeSettingsRequest]
        existing <- service.getSettings(body.tenantId)
        mtlsCertificateHeader = body.mtlsCertificateHeader.applyTo(existing.flatMap(_.mtlsCertificateHeader))
        mtlsCertificateEncoding = body.mtlsCertificateEncoding.applyTo(existing.flatMap(_.mtlsCertificateEncoding))
        clientAssertionMaxLifetimeSeconds = body.clientAssertionMaxLifetimeSeconds
          .orElse(existing.map(_.clientAssertionMaxLifetimeSeconds))
          .getOrElse(ChallengeSettingsRecord.DefaultClientAssertionMaxLifetimeSeconds)
        // #353: a tenant's profile is chosen when the tenant is created and never changed: its
        // clients were each admitted under it, and moving it afterwards would leave some of them
        // under a profile they were never held to. A request may name the profile it already
        // has -- the settings read back from `GET` carry it, so a client that round-trips them
        // does so unchanged -- and may leave it out; naming any other is refused.
        // Read from the repository, not the cache `existing` came from: a tenant created a
        // moment ago may not have reached the cache, and a stale miss would let a different
        // profile through.
        securityProfile <- service.getSecurityProfile(body.tenantId)
        _ <- ZIO.fail(BadRequest(
          s"securityProfile of tenant '${body.tenantId}' is $securityProfile and cannot be changed after the tenant is created",
        )).when(body.securityProfile.exists(_ != securityProfile))
        logoLight = body.logoLight.applyTo(existing.flatMap(_.logoLight))
        logoDark = body.logoDark.applyTo(existing.flatMap(_.logoDark))
        record = ChallengeSettingsRecord(
          tenantId = body.tenantId,
          phoneRegions = body.phoneRegions,
          emailRegistration = body.emailRegistration,
          defaultRoles = body.defaultRoles,
          submissionLimits = body.submissionLimits,
          otpLength = body.otpLength,
          otpResendAfter = body.otpResendAfter,
          passkeySettings = body.passkeySettings,
          passkeyPolicy = body.passkeyPolicy.applyTo(existing.flatMap(_.passkeyPolicy)),
          authConversationTtlSeconds = body.authConversationTtlSeconds.orElse(existing.map(_.authConversationTtlSeconds)).getOrElse(900),
          sessionTtlSeconds = body.sessionTtlSeconds.orElse(existing.map(_.sessionTtlSeconds)).getOrElse(86400),
          sessionIdleTtlSeconds = body.sessionIdleTtlSeconds.orElse(existing.flatMap(_.sessionIdleTtlSeconds)),
          userAgentTtlSeconds = body.userAgentTtlSeconds.orElse(existing.map(_.userAgentTtlSeconds)).getOrElse(15552000),
          ipHeader = body.ipHeader,
          acrVocabulary = body.acrVocabulary.orElse(existing.flatMap(_.acrVocabulary)),
          postLogoutRedirectUris = body.postLogoutRedirectUris.orElse(existing.map(_.postLogoutRedirectUris)).getOrElse(Nil),
          requireDpopNonce = body.requireDpopNonce.orElse(existing.map(_.requireDpopNonce)).getOrElse(false),
          mtlsCertificateHeader = mtlsCertificateHeader,
          mtlsCertificateEncoding = mtlsCertificateEncoding,
          signingKeyId = body.signingKeyId.applyTo(existing.flatMap(_.signingKeyId)),
          clientAssertionMaxLifetimeSeconds = clientAssertionMaxLifetimeSeconds,
          securityProfile = securityProfile,
          logoLight = logoLight,
          logoDark = logoDark,
        )
        validated <- ZIO.fromEither(ChallengeSettingsRecord.validate(record)).mapError(BadRequest(_))
        // Roles are only looked up while some credential can register, see `registrationPossible`.
        roles <- ZIO.service[RoleRepository]
        _ <- ZIO.when(ChallengeSettingsRecord.registrationPossible(validated)):
          ZIO.foreachDiscard(validated.defaultRoles): roleId =>
            roles.findRole(
              body.tenantId,
              roleId,
            ).someOrFail(BadRequest(s"defaultRoles names role '$roleId', which does not exist in tenant '${body.tenantId}'"))
        _ <- service.upsertSettings(validated)
          // A kid nothing can sign with, or one signing under a disallowed algorithm, is the
          // operator naming a key that does not fit, not a fault of this server -- and the
          // message says which of the reasons it is.
          .mapError {
            case error: ChallengeSettingsService.ValidationError => BadRequest(error.message)
            case other => other
          }
      yield Response.status(Status.NoContent)
    }

  /** The merge patch of the record, see [[PatchChallengeSettingsRequest]]: the members named are replaced, the others
    * keep what is stored. 404 when the tenant has no settings yet -- the first write is a `PUT`. */
  val patchChallengeSettingsEndpoint =
    Method.PATCH / "configuration" / "challenges" / "challenge-settings" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ChallengeSettingsService]
        body <- request.bodyAs[PatchChallengeSettingsRequest]
        _ <- service.patchSettings(body)
      yield Response.status(Status.NoContent))
        .catchAll:
          case ChallengeSettingsService.SettingsNotFound(_) => ZIO.succeed(Response.status(Status.NotFound))
          case error: ChallengeSettingsService.ValidationError => ZIO.fail(BadRequest(error.message))
          case error: Throwable => ZIO.fail(error)
    }
