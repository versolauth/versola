package versola.email

import versola.email.config.{EmailProviderConfig, MailgunConfig, SmtpConfig}
import versola.email.impl.{MailgunEmailService, SmtpEmailService}
import zio.*
import zio.http.Client

object EmailServiceProvider:
  
  def layer: ZLayer[EmailProviderConfig & Client, Throwable, EmailService] =
    ZLayer.fromZIO {
      for
        config <- ZIO.service[EmailProviderConfig]
        client <- ZIO.service[Client]
        service <- createEmailService(config, client)
      yield service
    }

  private def createEmailService(config: EmailProviderConfig, client: Client): Task[EmailService] =
    (config.smtp, config.mailgun) match
      case (Some(smtpConfig), None) =>
        ZIO.succeed(SmtpEmailService(smtpConfig))

      case (None, Some(mailgunConfig)) =>
        ZIO.succeed(MailgunEmailService(mailgunConfig, client))

      case (Some(_), Some(_)) =>
        ZIO.fail(new IllegalArgumentException("Multiple email providers configured. Only one provider should be configured."))

      case (None, None) =>
        ZIO.fail(new IllegalArgumentException("No email provider configured. Please configure either SMTP or Mailgun."))
