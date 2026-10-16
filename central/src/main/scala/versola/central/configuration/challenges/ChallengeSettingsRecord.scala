package versola.central.configuration.challenges

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

import java.net.IDN
import java.util.Locale
import java.util.regex.Pattern
import scala.util.Try

case class PasskeySettings(
    rpId: String,
    rpName: String,
    origins: List[String],
    userVerification: String,
) derives Schema, JsonCodec

/** How the reverse proxy in front of this tenant delivers a client certificate it terminated
  * mTLS for. There is no standard header or encoding for this -- every proxy invents its own
  * (nginx's `$ssl_client_escaped_cert`, Traefik's `passTLSClientCert`, cloud load balancers'
  * own headers again) -- so `auth` has to be told which shape to expect instead of guessing.
  */
enum MtlsCertificateEncoding derives Schema, JsonCodec:
  /** The full PEM (delimiters included), with `\n` and other reserved characters
    * percent-encoded. nginx's `$ssl_client_escaped_cert`; ingress-nginx forwards it unchanged
    * as `ssl-client-cert` when `auth-tls-pass-certificate-to-upstream` is set.
    */
  case urlEncodedPem

  /** The certificate's DER bytes, base64-encoded, with the PEM delimiters and newlines
    * stripped. Traefik's `passTLSClientCert` middleware with `pem: true`.
    */
  case base64Der

/** FAPI 2.0 Security Profile is close to a boolean today -- either a tenant's clients are
  * validated and enforced against it or they are not -- but stored as an enum so a narrower
  * or later profile (e.g. a FAPI 1.0 Advanced carve-out) needs no migration to add.
  *
  * See https://github.com/versolauth/versola/issues/353: registration/patch validation and
  * runtime enforcement against this profile land separately from the setting itself.
  */
enum SecurityProfile derives Schema, JsonCodec:
  case standard
  case fapi2

/** One country a tenant's phone sign-in accepts. Regions may share a [[dialCode]] (`KZ` and `RU`
  * both use `+7`); a number is accepted when it fits the [[length]] and [[regex]] of at least one
  * of them, so the pair is the unit, not the two taken separately.
  */
case class PhoneRegion(
    /** ISO 3166-1 alpha-2, unique within a tenant's list. */
    region: String,
    /** `+` and 1-4 digits. */
    dialCode: String,
    /** Digits after the dial code. */
    length: Int,
    /** Matched in full against those digits. */
    regex: String,
    /** Whether a number of this region may create an account on its own. Self-registration is
      * decided by the country, not by the client: a client that signs in by phone registers a
      * new user only when the number's region allows it.
      */
    registration: Boolean,
) derives Schema, JsonCodec

object PhoneRegion:
  val MinLength = 4
  val MaxLength = 14

  /** E.164: the dial code and the number together are at most 15 digits. */
  val MaxTotalDigits = 15

  private val RegionPattern = "[A-Z]{2}".r
  private val DialCodePattern = "\\+[1-9][0-9]{0,3}".r

  /** Why this list cannot be stored, if it cannot. */
  def validate(regions: List[PhoneRegion]): Option[String] =
    regions.iterator.map(validate).collectFirst { case Some(error) => error }
      .orElse:
        regions.groupBy(_.region).collectFirst { case (region, same) if same.size > 1 => s"phoneRegions lists region '$region' more than once" }

  private def validate(region: PhoneRegion): Option[String] =
    if !RegionPattern.matches(region.region) then Some(s"phoneRegions region '${region.region}' must be an ISO 3166-1 alpha-2 code")
    else if !DialCodePattern.matches(region.dialCode) then
      Some(s"phoneRegions dialCode '${region.dialCode}' of ${region.region} must be + and 1-4 digits")
    else if region.length < MinLength || region.length > MaxLength || region.dialCode.length - 1 + region.length > MaxTotalDigits then
      Some(
        s"phoneRegions length of ${region.region} must be between $MinLength and $MaxLength, and with the dial code at most $MaxTotalDigits digits",
      )
    else if Try(Pattern.compile(region.regex)).isFailure then
      Some(s"phoneRegions regex of ${region.region} is not a valid regular expression")
    else None

/** Who may create an account with an email address. Decided by the tenant, not by a client:
  * every client of a tenant that signs in by email offers the same registration. The same rules
  * apply when an existing user changes their address, so a tenant that only admits `example.com`
  * cannot be sidestepped from the profile.
  */
enum EmailRegistrationMode derives Schema, JsonCodec:
  /** Nobody registers with an email; existing accounts still sign in. */
  case closed

  /** Any domain registers, except the listed ones. */
  case open

  /** Only the listed domains register. */
  case allowlist

case class EmailRegistration(
    mode: EmailRegistrationMode,
    /** Lower-case ASCII (punycode) domains: `example.com` matches that domain only,
      * `*.example.com` its subdomains as well. A blocklist in [[EmailRegistrationMode.open]],
      * an allowlist in [[EmailRegistrationMode.allowlist]], ignored when closed. */
    domains: List[String],
    /** Refuse addresses at known throwaway-mailbox providers. */
    /** Refuse a local part with `+`, `=` or `#` (`user+tag@`), the way to mint endless addresses
      * for one mailbox. */
    blockSubaddress: Boolean,
) derives Schema, JsonCodec

object EmailRegistration:
  val MaxDomains = 500

  /** What a tenant starts with: no email registers until the operator chooses a mode. */
  val Closed: EmailRegistration =
    EmailRegistration(EmailRegistrationMode.closed, Nil, blockSubaddress = false)

  private val DomainPattern = "(\\*\\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+".r

  /** One list entry as it is stored: trimmed, lower-case, IDN labels in punycode. `None` when it
    * is not `example.com` or `*.example.com`. */
  def normalizeDomain(entry: String): Option[String] =
    val trimmed = entry.trim.toLowerCase(Locale.ROOT)
    Try(IDN.toASCII(trimmed.stripPrefix("*."))).toOption
      .map(ascii => (if trimmed.startsWith("*.") then "*." else "") + ascii.toLowerCase(Locale.ROOT))
      .filter(domain => domain.length <= 253 && DomainPattern.matches(domain))

  /** The setting with its domains normalized, or why it cannot be stored. */
  def normalize(setting: EmailRegistration): Either[String, EmailRegistration] =
    if setting.domains.sizeIs > MaxDomains then Left(s"emailRegistration lists more than $MaxDomains domains")
    else
      val entries = setting.domains.map(entry => entry -> normalizeDomain(entry))
      entries.collectFirst { case (entry, None) => entry } match
        case Some(bad) =>
          Left(s"emailRegistration domain '$bad' must be a domain such as example.com, or *.example.com for its subdomains")
        case None =>
          val normalized = entries.flatMap(_._2)
          normalized.groupBy(identity).collectFirst { case (domain, same) if same.size > 1 => domain } match
            case Some(duplicate) => Left(s"emailRegistration lists domain '$duplicate' more than once")
            case None => Right(setting.copy(domains = normalized))

case class ChallengeSettingsRecord(
    tenantId: TenantId,
    phoneRegions: List[PhoneRegion],
    /** Whether an email address may create an account, see [[EmailRegistration]]. */
    emailRegistration: EmailRegistration,
    /** Roles every self-registered account is granted, whichever credential it registers with. */
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
    acrVocabulary: Option[Map[String, List[String]]],
    postLogoutRedirectUris: List[String],
    /** RFC 9449 §8: whether a proof reaching auth's token or userinfo endpoint from one of
      * this tenant's clients must carry a server-issued nonce. */
    requireDpopNonce: Boolean,
    /** The header the proxy sets with the client certificate it terminated mTLS for. `None`
      * means this tenant's proxy does not terminate mTLS, so `auth` never looks for one --
      * matching a client's `mtlsAuth` is then impossible and registering it should be refused.
      */
    mtlsCertificateHeader: Option[String],
    /** How the certificate in `mtlsCertificateHeader` is encoded. Always present together
      * with `mtlsCertificateHeader` -- a header with no known encoding can't be parsed.
      */
    mtlsCertificateEncoding: Option[MtlsCertificateEncoding],
    /** The `kid` of the published JWKS key this tenant's tokens are signed with. The
      * algorithm follows from the key, so the two cannot disagree -- and during a rotation,
      * when two keys share one `alg`, a kid is the only thing that names a key at all.
      *
      * `None` falls back to auth matching its configured `jwt.private-key` against the synced
      * JWKS, which is the only thing a deployment whose keys are all verify-only can do.
      */
    signingKeyId: Option[String],
    /** RFC 7523 §3: how far into the future a `private_key_jwt` client assertion's `exp` may
      * sit. A `jti` has to be remembered for as long as the assertion bearing it is still
      * acceptable, so this is also the retention the replay guard is sized on -- raising it
      * costs storage there, and lowering it refuses assertions from clients that mint
      * long-lived ones.
      */
    clientAssertionMaxLifetimeSeconds: Int,
    /** Which FAPI profile this tenant's clients are held to. `fapi2` is the default for new
      * tenants -- see [[ChallengeSettingsRecord.DefaultSecurityProfile]]. */
    securityProfile: SecurityProfile,
) derives Schema, JsonCodec

object ChallengeSettingsRecord:
  /** Seeded by bootstrap with no permissions beyond access to the account itself. */
  val DefaultRoleId: RoleId = RoleId("user")

  /** Five minutes: what client libraries mint by default, and short enough that the replay
    * guard's retention is measured in minutes rather than hours. */
  val DefaultClientAssertionMaxLifetimeSeconds = 300

  /** FAPI 2.0 for every new tenant, and -- per the migration that introduced this column --
    * every tenant that existed before it too: there were no external consumers of this
    * deployment yet, only the seed tenant, so nothing is grandfathered onto `standard`. */
  val DefaultSecurityProfile: SecurityProfile = SecurityProfile.fapi2

  /** The window the replay guard in `auth` is sized to cover. A tenant cannot ask for a
    * longer one, because a `jti` it could no longer remember for the assertion's whole life
    * is a replay it could no longer detect. */
  val MaxClientAssertionMaxLifetimeSeconds = 900

  /** Below this, ordinary clock skew between a client and this server starts refusing
    * assertions that were honestly minted. */
  val MinClientAssertionMaxLifetimeSeconds = 30
