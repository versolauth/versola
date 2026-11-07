package versola.central.configuration.delivery

import versola.util.UnitSpecBase
import zio.test.*

/** The rules `PUT /configuration/delivery-settings` holds each block to. The refusals are what
  * keeps a secret out of the stored JSON (credentials in the URL) and a header out of a mail
  * (control characters), so each has a case of its own.
  */
object DeliverySettingsRecordSpec extends UnitSpecBase:

  /** A test environment, a production one, and a production one where the operator allows private targets. */
  private val nonProd = DeliveryRules(production = false, allowPrivateTargets = false)
  private val prod = DeliveryRules(production = true, allowPrivateTargets = false)
  private val prodPrivate = DeliveryRules(production = true, allowPrivateTargets = true)

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

  private val refusedSmtpTargets: List[(String, String)] = List(
    ("localhost", "loopback"),
    ("127.0.0.1", "loopback"),
    ("[::1]", "loopback"),
    ("2130706433", "loopback"),
    ("169.254.169.254", "link-local"),
    ("metadata.google.internal", "link-local"),
    ("0.0.0.0", "unspecified"),
  )

  private val alwaysRefusedUrls: List[String] = List(
    "https://localhost/send",
    "https://127.0.0.1:8443/send",
    "https://[::1]/send",
    "https://169.254.169.254/latest/meta-data",
    "https://metadata.google.internal/computeMetadata/v1/",
    "https://2130706433/send",
  )

  private val privateUrls: List[String] =
    List("https://10.0.0.5/send", "https://172.16.1.1/send", "https://192.168.0.10/send", "https://[fd00::1]/send")

  def spec = suite("DeliverySettingsRecord")(
    suite("the target of a saved block")(
      test("a mail server on loopback, link-local or an unspecified address is refused, with or without private targets allowed") {
        assertTrue(
          refusedSmtpTargets.forall((host, _) => SmtpDeliverySettings.validate(smtp.copy(host = host), nonProd).exists(_.startsWith("smtp.host"))),
          refusedSmtpTargets.forall((host, _) => SmtpDeliverySettings.validate(smtp.copy(host = host), prodPrivate).exists(_.startsWith("smtp.host"))),
        )
      },
      test("a mail server on a private address is refused unless the operator allows private targets, and the message says how") {
        val refused = SmtpDeliverySettings.validate(smtp.copy(host = "10.1.2.3"), prod)
        assertTrue(
          refused.exists(_.contains("allow-private-delivery-targets")),
          SmtpDeliverySettings.validate(smtp.copy(host = "10.1.2.3"), prodPrivate).isEmpty,
        )
      },
      test("a mail server in production is on a mail port only, in a test environment on any") {
        assertTrue(
          List(25, 465, 587, 2525).forall(port => SmtpDeliverySettings.validate(smtp.copy(port = port), prod).isEmpty),
          List(
            22, 5432, 8080, 6379, 9200,
          ).forall(port => SmtpDeliverySettings.validate(smtp.copy(port = port), prod).exists(_.startsWith("smtp.port"))),
          SmtpDeliverySettings.validate(smtp.copy(port = 1025), nonProd).isEmpty,
        )
      },
      test("a provider on loopback, link-local or metadata is refused, however the address is written") {
        assertTrue(
          alwaysRefusedUrls.forall(url =>
            OtpProviderDeliverySettings.validate(otpProvider.copy(url = url), nonProd).exists(_.startsWith("otpProvider.url")),
          ),
          alwaysRefusedUrls.forall(url =>
            OtpProviderDeliverySettings.validate(otpProvider.copy(url = url), prodPrivate).exists(_.startsWith("otpProvider.url")),
          ),
        )
      },
      test("a provider on a private address follows the operator's setting") {
        assertTrue(
          privateUrls.forall(url => OtpProviderDeliverySettings.validate(otpProvider.copy(url = url), prod).exists(_.contains("private network"))),
          privateUrls.forall(url => OtpProviderDeliverySettings.validate(otpProvider.copy(url = url), prodPrivate).isEmpty),
        )
      },
      test("a provider in production is called over https; in a test environment http is accepted") {
        val plain = otpProvider.copy(url = "http://sms.example.com/send")
        assertTrue(
          OtpProviderDeliverySettings.validate(plain, prod).exists(_.contains("https")),
          OtpProviderDeliverySettings.validate(plain.copy(username = None), prod).exists(_.contains("https")),
          OtpProviderDeliverySettings.validate(plain, nonProd).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider, prod).isEmpty,
        )
      },
      test("the refusal names the field and the class, never the address") {
        val message = OtpProviderDeliverySettings.validate(otpProvider.copy(url = "https://169.254.169.254/latest"), prod).getOrElse("")
        assertTrue(message.startsWith("otpProvider.url"), !message.contains("169.254"))
      },
    ),
    suite("SmtpDeliverySettings.validate")(
      (test("accepts a complete block") {
        assertTrue(SmtpDeliverySettings.validate(smtp, nonProd).isEmpty)
      } :: refusedSmtp.map { (what, block, field) =>
        test(s"refuses $what, naming $field") {
          assertTrue(SmtpDeliverySettings.validate(block, nonProd).exists(_.startsWith(field)))
        }
      })*,
    ),
    suite("OtpProviderDeliverySettings.validate")(
      (test("accepts a complete block, with or without a username") {
        assertTrue(
          OtpProviderDeliverySettings.validate(otpProvider, nonProd).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider.copy(username = None), nonProd).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider.copy(url = "http://sms-gateway.internal:8080/send"), nonProd).isEmpty,
          OtpProviderDeliverySettings.validate(otpProvider.copy(url = "https://sms.example.com:65535/send"), nonProd).isEmpty,
        )
      } :: refusedOtpProvider.map { (what, block, field) =>
        test(s"refuses $what, naming $field") {
          assertTrue(OtpProviderDeliverySettings.validate(block, nonProd).exists(_.startsWith(field)))
        }
      })*,
    ),
  )
