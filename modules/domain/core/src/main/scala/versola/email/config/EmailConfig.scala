package versola.email.config

import versola.email.model.EmailAddress

case class SmtpConfig(
    host: String,
    port: Int,
    username: String,
    password: String,
    fromAddress: EmailAddress,
    fromName: Option[String],
    useTls: Option[Boolean],
    useStartTls: Option[Boolean],
    connectionTimeout: Option[Int],
    timeout: Option[Int],
):
  def withDefaults: SmtpConfig = copy(
    useTls = useTls.orElse(Some(true)),
    useStartTls = useStartTls.orElse(Some(true)),
    connectionTimeout = connectionTimeout.orElse(Some(10000)),
    timeout = timeout.orElse(Some(10000))
  )

case class MailgunConfig(
    apiKey: String,
    domain: String,
    fromAddress: EmailAddress,
    fromName: Option[String],
    baseUrl: Option[String],
):
  def withDefaults: MailgunConfig = copy(
    baseUrl = baseUrl.orElse(Some("https://api.mailgun.net/v3"))
  )

case class EmailProviderConfig(
    smtp: Option[SmtpConfig],
    mailgun: Option[MailgunConfig],
)
