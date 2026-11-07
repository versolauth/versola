package versola.central.configuration.delivery

import versola.util.delivery.DeliveryTargetPolicy
import versola.util.{Email, Secret}
import zio.http.URL
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

import java.net.URI
import scala.util.Try

/** How auth delivers one-time codes and temporary passwords: an SMTP server for email and an
  * HTTP provider for SMS. One record per deployment, the counterpart of the `smtp` and
  * `otp-provider` blocks of `auth.conf`: a block stored here is used instead of the matching block of the file (#832).
  *
  * The password of each block is kept apart from the rest of it, so that no type that is ever
  * serialised -- the admin API's responses, the stored JSON -- can carry one by accident. The
  * passwords are encrypted at rest under `clientSecretsSecret` the way client secrets are, and
  * read back decrypted by [[DeliverySettingsService]]; a record straight from the repository
  * still holds the ciphertext.
  */
case class DeliverySettingsRecord(
    smtp: Option[SmtpDeliverySettings],
    smtpPassword: Option[Secret],
    otpProvider: Option[OtpProviderDeliverySettings],
    otpProviderPassword: Option[Secret],
)

/** The `smtp` block of `auth.conf` without its password. `enabled` lets an operator keep the
  * server on record while nothing is sent through it.
  */
case class SmtpDeliverySettings(
    host: String,
    port: Int,
    username: String,
    from: String,
    subject: String,
    startTls: Boolean,
    enabled: Boolean,
) derives Schema, JsonCodec

/** What a write is held to beyond the shape of the block, which depends on the deployment: whether it serves users, and
  * whether the operator allows targets on private networks ([[DeliveryTargetPolicy]]). Messages name the field and the
  * rule, never a value. */
case class DeliveryRules(production: Boolean, allowPrivateTargets: Boolean):
  def policy: DeliveryTargetPolicy = DeliveryTargetPolicy(allowPrivate = allowPrivateTargets)

object DeliveryRules:
  /** The ports a mail submission or relay is served on: SMTP, SMTPS, submission, and the common alternative to port 25. A
    * production server on another port is refused, so the form cannot be used to probe the ports of internal services. */
  val ProductionSmtpPorts: Set[Int] = Set(25, 465, 587, 2525)

object SmtpDeliverySettings:
  def validate(settings: SmtpDeliverySettings, rules: DeliveryRules): Option[String] =
    if settings.host.trim.isEmpty then Some("smtp.host must not be empty")
    else if settings.port < 1 || settings.port > 65535 then Some("smtp.port must be between 1 and 65535")
    else if settings.username.trim.isEmpty then Some("smtp.username must not be empty")
    else if Email.from(settings.from).isLeft then Some("smtp.from must be an email address")
    else if settings.subject.trim.isEmpty then Some("smtp.subject must not be empty")
    // The subject becomes a mail header and the host a connection target: a line break in either
    // is a header of its own, so none of the control characters gets as far as auth.
    else if hasControlCharacter(settings.host) then Some("smtp.host must not contain control characters")
    else if hasControlCharacter(settings.username) then Some("smtp.username must not contain control characters")
    else if hasControlCharacter(settings.subject) then Some("smtp.subject must not contain control characters")
    else if rules.production && !DeliveryRules.ProductionSmtpPorts.contains(settings.port) then
      Some(s"smtp.port must be one of ${DeliveryRules.ProductionSmtpPorts.toList.sorted.mkString(", ")} in production")
    else
      rules.policy.refusalOfHost(settings.host).map(kind => targetRefusal("smtp.host", kind, rules))

/** The `otp-provider` block of `auth.conf` without its password: the request auth sends for each
  * SMS, with `{{phone}}` and `{{message}}` in the body entries standing for the recipient and
  * the text. A `username` makes the request carry HTTP Basic credentials.
  *
  * The body entries and the URL, query string included, are stored and returned as they are: a
  * provider that wants its API key in the body or the query is not served by this record yet,
  * and the console must say so rather than invite the key into a field every `security:read`
  * holder can read.
  */
case class OtpProviderDeliverySettings(
    method: String,
    url: String,
    username: Option[String],
    body: Map[String, String],
    enabled: Boolean,
) derives Schema, JsonCodec

object OtpProviderDeliverySettings:
  val Methods: Set[String] = Set("GET", "POST", "PUT", "PATCH")

  def validate(settings: OtpProviderDeliverySettings, rules: DeliveryRules): Option[String] =
    if !Methods.contains(settings.method) then Some(s"otpProvider.method must be one of ${Methods.toList.sorted.mkString(", ")}")
    else
      parseHttpUrl(settings.url) match
        case None => Some("otpProvider.url must be an absolute HTTP(S) URL")
        // `https://user:secret@host/` would put the secret into the stored JSON and into every
        // answer of the admin API, which is exactly what the password field exists to avoid.
        case Some(uri) if Option(uri.getRawUserInfo).isDefined =>
          Some("otpProvider.url must not carry credentials; use username and the password field")
        // Auth reads the address with another parser than `java.net.URI`: one that central accepts and auth cannot read would
        // be a saved block that sends nothing, so it is refused here.
        case Some(_) if URL.decode(settings.url).isLeft => Some("otpProvider.url must be an absolute HTTP(S) URL")
        // The one-time codes and the Basic credentials travel to this address: in production they do not go in clear.
        case Some(uri) if rules.production && !uri.getScheme.equalsIgnoreCase("https") =>
          Some("otpProvider.url must use https in production")
        case Some(uri) if rules.policy.refusalOfHost(uri.getHost).isDefined =>
          rules.policy.refusalOfHost(uri.getHost).map(kind => targetRefusal("otpProvider.url", kind, rules))
        case Some(_) =>
          if settings.username.exists(_.trim.isEmpty) then Some("otpProvider.username must not be empty when present")
          else if settings.username.exists(hasControlCharacter) then Some("otpProvider.username must not contain control characters")
          else if settings.body.keys.exists(_.trim.isEmpty) then Some("otpProvider.body must not have an empty key")
          else None

  private def parseHttpUrl(value: String): Option[URI] =
    Try(URI(value)).toOption.filter: uri =>
      val httpScheme = Option(uri.getScheme).exists(scheme => scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
      val portAbsentOrValid = uri.getPort == -1 || (1 to 65535).contains(uri.getPort)
      uri.isAbsolute && httpScheme && Option(uri.getHost).exists(_.nonEmpty) && portAbsentOrValid

/** `private` is the one a setting lifts, so the message says which; the others are refused whatever the operator sets. */
private def targetRefusal(field: String, kind: String, rules: DeliveryRules): String =
  if kind == "private" then s"$field must not point at a private network address; the operator can allow it with allow-private-delivery-targets"
  else s"$field must not point at a $kind address"

private def hasControlCharacter(value: String): Boolean =
  value.exists(Character.isISOControl)
