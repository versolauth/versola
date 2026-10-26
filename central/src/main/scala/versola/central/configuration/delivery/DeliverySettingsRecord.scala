package versola.central.configuration.delivery

import versola.util.{Email, Secret}
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

import java.net.URI
import scala.util.Try

/** How auth delivers one-time codes and temporary passwords: an SMTP server for email and an
  * HTTP provider for SMS. One record per deployment, the counterpart of the `smtp` and
  * `otp-provider` blocks of `auth.conf`, which it replaces once it is stored (#832).
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
    else None

/** The `otp-provider` block of `auth.conf` without its password: the request auth sends for each
  * SMS, with `{{phone}}` and `{{message}}` in the body entries standing for the recipient and
  * the text. A `username` makes the request carry HTTP Basic credentials.
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
    else if !isHttpUrl(settings.url) then Some("otpProvider.url must be an absolute HTTP(S) URL")
    else if settings.username.exists(_.trim.isEmpty) then Some("otpProvider.username must not be empty when present")
    else if settings.body.keys.exists(_.trim.isEmpty) then Some("otpProvider.body must not have an empty key")
    else None

  private def isHttpUrl(value: String): Boolean =
    Try(URI(value)).toOption.exists: uri =>
      val httpScheme = Option(uri.getScheme).exists(scheme => scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
      uri.isAbsolute && httpScheme && Option(uri.getHost).exists(_.nonEmpty) && uri.getPort <= 65535
