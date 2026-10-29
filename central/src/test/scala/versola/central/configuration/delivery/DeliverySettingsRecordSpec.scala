package versola.central.configuration.delivery

import versola.util.UnitSpecBase
import zio.test.*

/** The rules `PUT /configuration/delivery-settings` holds each block to. The refusals are what
  * keeps a secret out of the stored JSON (credentials in the URL) and a header out of a mail
  * (control characters), so each has a case of its own.
  */
object DeliverySettingsRecordSpec extends UnitSpecBase:

  private val smtp = SmtpDeliverySettings(
    host = "smtp.example.com",
    port = 587,
    username = "mailer",
    from = "noreply@example.com",
    subject = "Your code",
    startTls = true,
    enabled = true,
  )

  private val otpProvider = OtpProviderDeliverySettings(
    method = "POST",
    url = "https://sms.example.com/send",
    username = Some("sms"),
    body = Map("phones" -> "{{phone}}", "mes" -> "{{message}}"),
    enabled = true,
  )

  private val refusedSmtp: List[(String, SmtpDeliverySettings, String)] = List(
    ("an empty host", smtp.copy(host = " "), "smtp.host"),
    ("port 0", smtp.copy(port = 0), "smtp.port"),
    ("a port above 65535", smtp.copy(port = 65536), "smtp.port"),
    ("an empty username", smtp.copy(username = ""), "smtp.username"),
    ("a sender that is not an address", smtp.copy(from = "not-an-address"), "smtp.from"),
    ("an empty subject", smtp.copy(subject = "  "), "smtp.subject"),
    ("a line break in the subject", smtp.copy(subject = "Code\r\nBcc: x@example.com"), "smtp.subject"),
    ("a line break in the host", smtp.copy(host = "smtp.example.com\n"), "smtp.host"),
    ("a control character in the username", smtp.copy(username = "mail\u0000er"), "smtp.username"),
  )

  private val refusedOtpProvider: List[(String, OtpProviderDeliverySettings, String)] = List(
    ("a method outside GET, POST, PUT, PATCH", otpProvider.copy(method = "DELETE"), "otpProvider.method"),
    ("a lower-case method", otpProvider.copy(method = "post"), "otpProvider.method"),
    ("a URL without a scheme", otpProvider.copy(url = "sms.example.com/send"), "otpProvider.url"),
    ("a URL with another scheme", otpProvider.copy(url = "ftp://sms.example.com/send"), "otpProvider.url"),
    ("a URL without a host", otpProvider.copy(url = "https:///send"), "otpProvider.url"),
    ("a URL with port 0", otpProvider.copy(url = "https://sms.example.com:0/send"), "otpProvider.url"),
    ("credentials in the URL", otpProvider.copy(url = "https://sms:topsecret@sms.example.com/send"), "otpProvider.url"),
    ("an empty username", otpProvider.copy(username = Some(" ")), "otpProvider.username"),
    ("a control character in the username", otpProvider.copy(username = Some("sms\r")), "otpProvider.username"),
    ("an empty body key", otpProvider.copy(body = Map("" -> "{{phone}}")), "otpProvider.body"),
  )

  def spec = suite("DeliverySettingsRecord")(
    suite("SmtpDeliverySettings.validate")(
      (test("accepts a complete block") {
        assertTrue(SmtpDeliverySettings.validate(smtp).isEmpty)
      } :: refusedSmtp.map { (what, block, field) =>
        test(s"refuses $what, naming $field") {
          assertTrue(SmtpDeliverySettings.validate(block).exists(_.startsWith(field)))
        }
      })*,
    ),
    suite("OtpProviderDeliverySettings.validate")(
      (test("accepts a complete block, with or without a username") {
        assertTrue(
          OtpProviderDeliverySettings.validate(otpProvider).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider.copy(username = None)).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider.copy(url = "http://sms-gateway.internal:8080/send")).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider.copy(url = "https://sms.example.com:65535/send")).isEmpty,
        )
      } :: refusedOtpProvider.map { (what, block, field) =>
        test(s"refuses $what, naming $field") {
          assertTrue(OtpProviderDeliverySettings.validate(block).exists(_.startsWith(field)))
        }
      })*,
    ),
  )
