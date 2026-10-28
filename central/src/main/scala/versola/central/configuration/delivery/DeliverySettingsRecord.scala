package versola.central.configuration.delivery

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

object SmtpDeliverySettings:
  def validate(settings: SmtpDeliverySettings): Option[String] =
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
    else None

  /** Whether `a` and `b` are the same server and the same account, reached the same way: the only case in which a password
    * saved for one may be used for the other. Anything else would let whoever may change the host collect the password by
    * pointing it at a server of their own, without ever reading it, or send it in the clear by switching STARTTLS off.
    */
  def sameServer(a: SmtpDeliverySettings, b: SmtpDeliverySettings): Boolean =
    a.host.trim.equalsIgnoreCase(b.host.trim) && a.port == b.port && a.username == b.username && a.startTls == b.startTls

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

  def validate(settings: OtpProviderDeliverySettings): Option[String] =
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
        case Some(_) =>
          if settings.username.exists(_.trim.isEmpty) then Some("otpProvider.username must not be empty when present")
          else if settings.username.exists(hasControlCharacter) then Some("otpProvider.username must not contain control characters")
          else if settings.body.keys.exists(_.trim.isEmpty) then Some("otpProvider.body must not have an empty key")
          else None

  /** Whether `a` and `b` reach the same origin (scheme, host, port) as the same account: the only case in which a
    * password saved for one may be used for the other, for the reason given at [[SmtpDeliverySettings.sameServer]].
    * The path may change: it does not decide who receives the credentials.
    */
  def sameEndpoint(a: OtpProviderDeliverySettings, b: OtpProviderDeliverySettings): Boolean =
    (parseHttpUrl(a.url), parseHttpUrl(b.url)) match
      case (Some(x), Some(y)) =>
        x.getScheme.equalsIgnoreCase(y.getScheme) && x.getHost.equalsIgnoreCase(y.getHost) &&
        effectivePort(x) == effectivePort(y) && a.username == b.username
      case _ => false

  private def effectivePort(uri: URI): Int =
    if uri.getPort != -1 then uri.getPort else if uri.getScheme.equalsIgnoreCase("https") then 443 else 80

  private def parseHttpUrl(value: String): Option[URI] =
    Try(URI(value)).toOption.filter: uri =>
      val httpScheme = Option(uri.getScheme).exists(scheme => scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
      val portAbsentOrValid = uri.getPort == -1 || (1 to 65535).contains(uri.getPort)
      uri.isAbsolute && httpScheme && Option(uri.getHost).exists(_.nonEmpty) && portAbsentOrValid

private def hasControlCharacter(value: String): Boolean =
  value.exists(Character.isISOControl)
