package versola.central.configuration.challenges

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.clients.{ClientProfileViolation, OAuthClientService}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.Patch.applyTo
import versola.util.http.{BadRequest, Controller}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

object OtpChallengeController extends Controller:
  type Env = Tracing & OtpChallengeService & ChallengeSettingsService & ResourceService & CentralConfig & EdgeService & OAuthClientService

  /** What refusing a switch to a profile answers with: every client the profile would not
    * admit, and why. `409` rather than `400`: the request is well formed, and would be
    * accepted as it stands once the tenant's clients are brought into conformance. */
  case class SecurityProfileConflict(
      error: String,
      message: String,
      securityProfile: SecurityProfile,
      violations: Vector[ClientProfileViolation],
  ) derives JsonCodec

  private case class SecurityProfileRefused(conflict: SecurityProfileConflict)
    extends RuntimeException(conflict.message)

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
      (for
        _        <- authorizeBasic(request)
        service  <- ZIO.service[ChallengeSettingsService]
        body     <- request.bodyAs[UpsertChallengeSettingsRequest]
        existing <- service.getSettings(body.tenantId)
        mtlsCertificateHeader   = body.mtlsCertificateHeader.applyTo(existing.flatMap(_.mtlsCertificateHeader))
        mtlsCertificateEncoding = body.mtlsCertificateEncoding.applyTo(existing.flatMap(_.mtlsCertificateEncoding))
        clientAssertionMaxLifetimeSeconds = body.clientAssertionMaxLifetimeSeconds
          .orElse(existing.map(_.clientAssertionMaxLifetimeSeconds))
          .getOrElse(ChallengeSettingsRecord.DefaultClientAssertionMaxLifetimeSeconds)
        // #353: switching a tenant onto FAPI 2.0 is refused while any of its clients would
        // violate it, rather than applied and left for those clients to fail at their next
        // patch -- or, for public ones, at their next token request. Only on the switch: a
        // tenant already on the profile (every tenant after the migration that introduced it)
        // keeps whatever it was holding, and has to stay editable while it fixes that.
        // Read from the repository, not the cache `existing` came from: a switch back onto the
        // profile moments after leaving it must not be taken for no switch at all.
        stored <- service.getSecurityProfile(body.tenantId)
        // Defaulted from `stored`, not `existing.securityProfile`: a request that omits
        // `securityProfile` (it is patching something else entirely) must keep whatever profile
        // the tenant actually holds right now, not whatever the cache last saw it holding --
        // otherwise it silently reverts a switch a concurrent request just committed, with no
        // error and no sign to either caller.
        securityProfile = body.securityProfile.getOrElse(stored)
        violations <-
          if securityProfile == SecurityProfile.fapi2 && (existing.isEmpty || stored != SecurityProfile.fapi2) then
            ZIO.serviceWithZIO[OAuthClientService](_.profileViolations(body.tenantId, securityProfile))
          else ZIO.succeed(Vector.empty)
        _ <- ZIO.fail(SecurityProfileRefused(SecurityProfileConflict(
          error = "security_profile_violations",
          message = s"${violations.size} client(s) of tenant '${body.tenantId}' would violate $securityProfile",
          securityProfile = securityProfile,
          violations = violations,
        ))).when(violations.nonEmpty)
        // One setting stored in two columns: a header no encoding says how to read, and an
        // encoding that names no header, both leave `auth` with a tenant whose mutual TLS is
        // off while central reports it configured.
        _ <- ZIO.fail(BadRequest("mtlsCertificateHeader and mtlsCertificateEncoding must be set or cleared together"))
          .when(mtlsCertificateHeader.isDefined != mtlsCertificateEncoding.isDefined)
        _ <- ZIO.fail(BadRequest(
          s"clientAssertionMaxLifetimeSeconds must be between ${ChallengeSettingsRecord.MinClientAssertionMaxLifetimeSeconds}" +
            s" and ${ChallengeSettingsRecord.MaxClientAssertionMaxLifetimeSeconds}",
        )).when(
          clientAssertionMaxLifetimeSeconds < ChallengeSettingsRecord.MinClientAssertionMaxLifetimeSeconds ||
            clientAssertionMaxLifetimeSeconds > ChallengeSettingsRecord.MaxClientAssertionMaxLifetimeSeconds,
        )
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
            clientAssertionMaxLifetimeSeconds,
            securityProfile,
          ),
        )
          // A kid nothing can sign with, or one signing under a disallowed algorithm, is the
          // operator naming a key that does not fit, not a fault of this server -- and the
          // message says which of the reasons it is.
          .mapError {
            case error: ChallengeSettingsService.ValidationError => BadRequest(error.message)
            case other                                          => other
          }
      yield Response.status(Status.NoContent)).catchSome:
        case SecurityProfileRefused(conflict) =>
          ZIO.succeed(Response.json(conflict.toJson).status(Status.Conflict))
    }