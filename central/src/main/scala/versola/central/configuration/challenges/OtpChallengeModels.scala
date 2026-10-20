package versola.central.configuration.challenges

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.Patch
import versola.util.Patch.applyTo
import zio.json.{JsonCodec, jsonNoExtraFields}
import zio.schema.{Schema, derived}

enum OtpTemplatePurpose derives Schema, JsonCodec:
  case otp, password

enum OtpTemplateChannel derives Schema, JsonCodec:
  case sms, email

case class GetOtpTemplatesResponse(templates: Vector[OtpTemplateRecord]) derives Schema, JsonCodec

case class UpsertOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
    purpose: OtpTemplatePurpose,
    channel: OtpTemplateChannel,
) derives Schema, JsonCodec

case class DeleteOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
    purpose: OtpTemplatePurpose,
    channel: OtpTemplateChannel,
) derives Schema, JsonCodec

case class GetChallengeSettingsResponse(settings: Option[ChallengeSettingsRecord]) derives Schema, JsonCodec

case class GetAllChallengeSettingsResponse(settings: Vector[ChallengeSettingsRecord]) derives Schema, JsonCodec

case class UpsertChallengeSettingsRequest(
    tenantId: TenantId,
    phoneRegions: List[PhoneRegion],
    emailRegistration: EmailRegistration,
    defaultRoles: Set[RoleId],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Option[Int],
    sessionTtlSeconds: Option[Int],
    sessionIdleTtlSeconds: Option[Int],
    userAgentTtlSeconds: Option[Int],
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[String]]],
    postLogoutRedirectUris: Option[List[String]],
    requireDpopNonce: Option[Boolean],
    /** Null clears the header, which is how a tenant's mutual TLS is turned off; absent
      * keeps the stored value. Sent together with `mtlsCertificateEncoding` -- a header with
      * no encoding cannot be parsed, and an encoding with no header names nothing. */
    mtlsCertificateHeader: Option[Patch[String]],
    mtlsCertificateEncoding: Option[Patch[MtlsCertificateEncoding]],
    /** The `kid` of the JWKS key this tenant signs with. Null clears the selection, falling
      * back to auth's configured private key; absent keeps the stored one. Rejected unless
      * central holds a private key for that kid -- see `ChallengeSettingsService`.
      *
      * No default: a field with one decodes an explicit `null` as the default rather than as
      * `Some(Deleted)`, which would leave the selection impossible to clear. */
    signingKeyId: Option[Patch[String]],
    /** RFC 7523 §3: furthest into the future a client assertion's `exp` may sit; absent
      * keeps the stored value. */
    clientAssertionMaxLifetimeSeconds: Option[Int],
    /** Which FAPI profile this tenant's clients are held to. Chosen when the tenant is
      * created ([[versola.central.configuration.CreateTenantRequest]]) and fixed from then on:
      * absent, or naming the stored profile, leaves it as it is; any other value is refused. */
    securityProfile: Option[SecurityProfile],
    /** The logo pair, see [[ChallengeSettingsRecord.logoLight]]. Null clears one, absent keeps the stored one; after
      * both are applied they must be set or cleared together. Surrounding blanks are not trimmed: send a clean URL.
      *
      * No default, for the same reason as `signingKeyId`. */
    logoLight: Option[Patch[String]],
    logoDark: Option[Patch[String]],
) derives Schema, JsonCodec

/** A JSON merge patch (RFC 7396) of a tenant's [[ChallengeSettingsRecord]], at the level of the record's own members: the
  * ones this body names are replaced, the others keep their stored value. A member's value is replaced whole, never
  * merged into -- `submissionLimits`, `passkeySettings`, `emailRegistration` and `acrVocabulary` are sent in full.
  *
  * Absent keeps, a value replaces, `null` clears: only the optional members can be cleared (`sessionIdleTtlSeconds`,
  * `acrVocabulary`, `mtlsCertificateHeader`, `mtlsCertificateEncoding`, `signingKeyId`, `logoLight`, `logoDark`), and a
  * `null` for any other member is refused rather than read as absent. An unknown key is refused too, so a misspelt
  * member cannot pass for an applied change.
  *
  * No member has a default, for the reason `signingKeyId` of [[UpsertChallengeSettingsRequest]] gives.
  */
@jsonNoExtraFields
case class PatchChallengeSettingsRequest(
    tenantId: TenantId,
    phoneRegions: Option[Patch[List[PhoneRegion]]],
    emailRegistration: Option[Patch[EmailRegistration]],
    defaultRoles: Option[Patch[Set[RoleId]]],
    submissionLimits: Option[Patch[SubmissionLimits]],
    otpLength: Option[Patch[Int]],
    otpResendAfter: Option[Patch[Int]],
    passkeySettings: Option[Patch[PasskeySettings]],
    authConversationTtlSeconds: Option[Patch[Int]],
    sessionTtlSeconds: Option[Patch[Int]],
    sessionIdleTtlSeconds: Option[Patch[Int]],
    userAgentTtlSeconds: Option[Patch[Int]],
    ipHeader: Option[Patch[String]],
    acrVocabulary: Option[Patch[Map[String, List[String]]]],
    postLogoutRedirectUris: Option[Patch[List[String]]],
    requireDpopNonce: Option[Patch[Boolean]],
    mtlsCertificateHeader: Option[Patch[String]],
    mtlsCertificateEncoding: Option[Patch[MtlsCertificateEncoding]],
    signingKeyId: Option[Patch[String]],
    clientAssertionMaxLifetimeSeconds: Option[Patch[Int]],
    /** Fixed when the tenant is created: naming the stored profile changes nothing, naming another is refused. */
    securityProfile: Option[Patch[SecurityProfile]],
    logoLight: Option[Patch[String]],
    logoDark: Option[Patch[String]],
) derives Schema, JsonCodec:

  /** `current` with this patch applied, validated as the record to be stored (the same rules as `PUT`, see
    * [[ChallengeSettingsRecord.validate]]), or why it cannot be. Pure, so the repository can run it on the row it
    * has locked. */
  def applyTo(current: ChallengeSettingsRecord): Either[String, ChallengeSettingsRecord] =
    for
      phoneRegions <- required(name = "phoneRegions", patch = this.phoneRegions, current = current.phoneRegions)
      emailRegistration <- required(name = "emailRegistration", patch = this.emailRegistration, current = current.emailRegistration)
      defaultRoles <- required(name = "defaultRoles", patch = this.defaultRoles, current = current.defaultRoles)
      submissionLimits <- required(name = "submissionLimits", patch = this.submissionLimits, current = current.submissionLimits)
      otpLength <- required(name = "otpLength", patch = this.otpLength, current = current.otpLength)
      otpResendAfter <- required(name = "otpResendAfter", patch = this.otpResendAfter, current = current.otpResendAfter)
      passkeySettings <- required(name = "passkeySettings", patch = this.passkeySettings, current = current.passkeySettings)
      authConversationTtlSeconds <-
        required(name = "authConversationTtlSeconds", patch = this.authConversationTtlSeconds, current = current.authConversationTtlSeconds)
      sessionTtlSeconds <- required(name = "sessionTtlSeconds", patch = this.sessionTtlSeconds, current = current.sessionTtlSeconds)
      userAgentTtlSeconds <- required(name = "userAgentTtlSeconds", patch = this.userAgentTtlSeconds, current = current.userAgentTtlSeconds)
      ipHeader <- required(name = "ipHeader", patch = this.ipHeader, current = current.ipHeader)
      postLogoutRedirectUris <-
        required(name = "postLogoutRedirectUris", patch = this.postLogoutRedirectUris, current = current.postLogoutRedirectUris)
      requireDpopNonce <- required(name = "requireDpopNonce", patch = this.requireDpopNonce, current = current.requireDpopNonce)
      clientAssertionMaxLifetimeSeconds <- required(
        name = "clientAssertionMaxLifetimeSeconds",
        patch = this.clientAssertionMaxLifetimeSeconds,
        current = current.clientAssertionMaxLifetimeSeconds,
      )
      securityProfile <- required(name = "securityProfile", patch = this.securityProfile, current = current.securityProfile)
      _ <- Either.cond(
        securityProfile == current.securityProfile,
        (),
        s"securityProfile of tenant '${current.tenantId}' is ${current.securityProfile} and cannot be changed after the tenant is created",
      )
      record = ChallengeSettingsRecord(
        tenantId = current.tenantId,
        phoneRegions = phoneRegions,
        emailRegistration = emailRegistration,
        defaultRoles = defaultRoles,
        submissionLimits = submissionLimits,
        otpLength = otpLength,
        otpResendAfter = otpResendAfter,
        passkeySettings = passkeySettings,
        authConversationTtlSeconds = authConversationTtlSeconds,
        sessionTtlSeconds = sessionTtlSeconds,
        sessionIdleTtlSeconds = this.sessionIdleTtlSeconds.applyTo(current.sessionIdleTtlSeconds),
        userAgentTtlSeconds = userAgentTtlSeconds,
        ipHeader = ipHeader,
        acrVocabulary = this.acrVocabulary.applyTo(current.acrVocabulary),
        postLogoutRedirectUris = postLogoutRedirectUris,
        requireDpopNonce = requireDpopNonce,
        mtlsCertificateHeader = this.mtlsCertificateHeader.applyTo(current.mtlsCertificateHeader),
        mtlsCertificateEncoding = this.mtlsCertificateEncoding.applyTo(current.mtlsCertificateEncoding),
        signingKeyId = this.signingKeyId.applyTo(current.signingKeyId),
        clientAssertionMaxLifetimeSeconds = clientAssertionMaxLifetimeSeconds,
        securityProfile = securityProfile,
        logoLight = this.logoLight.applyTo(current.logoLight),
        logoDark = this.logoDark.applyTo(current.logoDark),
      )
      validated <- ChallengeSettingsRecord.validate(record)
    yield validated

  private def required[A](name: String, patch: Option[Patch[A]], current: A): Either[String, A] =
    patch match
      case None => Right(current)
      case Some(Patch.Modified(value)) => Right(value)
      case Some(Patch.Deleted) => Left(s"$name cannot be cleared: it is not optional")
