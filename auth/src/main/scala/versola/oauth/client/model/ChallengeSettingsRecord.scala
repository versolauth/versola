package versola.oauth.client.model

import versola.role.model.RoleId
import versola.util.Email
import zio.json.JsonCodec

import java.util.Locale

case class PasskeySettings(
    rpId: String,
    rpName: String,
    origins: List[String],
    userVerification: String,
) derives JsonCodec

/** Mirrors central's `MtlsCertificateEncoding` -- see there for what each case means. */
enum MtlsCertificateEncoding derives JsonCodec:
  case urlEncodedPem
  case base64Der

/** Where a client certificate is to be read from for one tenant, once the two settings that
  * are only meaningful together are known to both be present. */
case class MtlsCertificateSource(
    header: String,
    encoding: MtlsCertificateEncoding,
)

/** Mirrors central's `SecurityProfile` -- see there for what each case means. */
enum SecurityProfile derives JsonCodec:
  case standard
  case fapi2

/** Mirrors central's `PhoneRegion` -- see there for what the fields mean. */
case class PhoneRegion(
    region: String,
    dialCode: String,
    length: Int,
    regex: String,
    /** Whether a number of this region may create an account on its own. */
    registration: Boolean,
) derives JsonCodec:
  // Compiled when the settings are decoded by the sync client, not on a request.
  private val pattern = java.util.regex.Pattern.compile(regex)

  /** Whether `national` -- the digits after this region's dial code -- is a number of this region. */
  def accepts(national: String): Boolean =
    national.length == length && national.forall(_.isDigit) && pattern.matcher(national).matches()

object PhoneRegion:
  /** The region an E.164 number belongs to. Several regions can share a dial code, and one dial
    * code can start another (`+1` and `+1242`), so the longest dial code that fits wins and
    * regions that share one are tried in the order the tenant listed them.
    */
  def resolve(regions: List[PhoneRegion], e164: String): Option[PhoneRegion] =
    regions
      .filter(region => e164.startsWith(region.dialCode))
      .sortBy(region => -region.dialCode.length)
      .find(region => region.accepts(e164.drop(region.dialCode.length)))

  /** Whether a new account may be created from this number. A list that names no region for it
    * opens nothing: self-registration is granted by a country, so no countries means no
    * registration (signing in is unaffected).
    */
  def allowsRegistration(regions: List[PhoneRegion], e164: String): Boolean =
    resolve(regions, e164).exists(_.registration)

  /** An empty list restricts nothing: the tenant has not chosen the countries it takes. */
  def allows(regions: List[PhoneRegion], e164: String): Boolean =
    regions.isEmpty || resolve(regions, e164).isDefined

/** Mirrors central's `EmailRegistrationMode`. */
enum EmailRegistrationMode derives JsonCodec:
  case closed, open, allowlist

/** Mirrors central's `EmailRegistration` -- see there for what the fields mean. The rules apply to
  * a new account and to an existing user who changes their address.
  */
case class EmailRegistration(
    mode: EmailRegistrationMode,
    domains: List[String],
    blockSubaddress: Boolean,
) derives JsonCodec:

  /** Whether this address may be used to create an account (or to become a user's address). */
  def allows(email: Email): Boolean =
    val (local, domain) = EmailRegistration.split(email)
    mode match
      case EmailRegistrationMode.closed => false
      case _ if blockSubaddress && EmailRegistration.isSubaddress(local) => false
      case EmailRegistrationMode.allowlist => domains.exists(EmailRegistration.matches(_, domain))
      case EmailRegistrationMode.open =>
        !domains.exists(EmailRegistration.matches(_, domain))

object EmailRegistration:
  /** The setting a tenant has until an operator chooses a mode. */
  val Closed: EmailRegistration = EmailRegistration(EmailRegistrationMode.closed, Nil, false)

  private def split(email: Email): (String, String) =
    val at = email.lastIndexOf('@')
    (email.substring(0, at + 1).stripSuffix("@"), email.substring(at + 1).toLowerCase(Locale.ROOT))

  private def isSubaddress(local: String): Boolean = local.exists(c => c == '+' || c == '=' || c == '#')

  /** `example.com` is that domain; `*.example.com` is any subdomain of it (not `example.com`). */
  private def matches(entry: String, domain: String): Boolean =
    val normalized = entry.toLowerCase(Locale.ROOT)
    if normalized.startsWith("*.") then domain.endsWith(normalized.drop(1)) else domain == normalized

case class ChallengeSettingsRecord(
    tenantId: TenantId,
    phoneRegions: List[PhoneRegion],
    emailRegistration: EmailRegistration,
    defaultRoles: Set[RoleId],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Int,
    sessionTtlSeconds: Int,
    sessionIdleTtlSeconds: Option[Int],
    userAgentTtlSeconds: Int,
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[PassedAuthFactor]]],
    postLogoutRedirectUris: List[String],
    /** RFC 9449 §8: whether a proof from one of this tenant's clients must carry a
      * server-issued nonce -- see [[versola.oauth.client.OAuthConfigurationService.requireDpopNonce]]. */
    requireDpopNonce: Boolean,
    mtlsCertificateHeader: Option[String],
    mtlsCertificateEncoding: Option[MtlsCertificateEncoding],
    /** The `kid` this tenant's tokens are signed with, selected in central. `None` falls back
      * to matching the configured `jwt.private-key` against the synced JWKS, which is all a
      * deployment whose keys are verify-only can do -- see [[versola.oauth.jwks.JwksService]]. */
    signingKeyId: Option[String],
    /** RFC 7523 §3: furthest into the future a client assertion's `exp` may sit, and so how
      * long its `jti` is remembered against replay -- see
      * [[versola.oauth.client.OAuthConfigurationService.getClientAssertionMaxLifetime]]. */
    clientAssertionMaxLifetimeSeconds: Int,
    /** Which FAPI profile this tenant's clients are held to -- see
      * [[versola.oauth.client.OAuthConfigurationService.getSecurityProfile]]. */
    securityProfile: SecurityProfile,
) derives JsonCodec

object ChallengeSettingsRecord:
  /** Mirrors central's default, and applies where a tenant has no settings row at all. */
  val DefaultClientAssertionMaxLifetime: zio.Duration = zio.Duration.fromSeconds(300)

  /** Mirrors central's default, and applies where a tenant has no settings row at all. */
  val DefaultSecurityProfile: SecurityProfile = SecurityProfile.fapi2
