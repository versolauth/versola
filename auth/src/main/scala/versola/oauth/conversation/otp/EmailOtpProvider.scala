package versola.oauth.conversation.otp

import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.{CoreConfig, Email}
import zio.{Task, URLayer, ZIO, ZLayer}

import java.util.Properties

trait EmailOtpProvider:
  def sendOtp(
      email: Email,
      code: OtpCode,
      template: OtpTemplate,
  ): Task[Unit]

object EmailOtpProvider:
  val live: URLayer[CoreConfig, EmailOtpProvider] =
    ZLayer.fromFunction: (config: CoreConfig) =>
      config.smtp match
        case Some(smtp) => SMTPOtpProvider(smtp)
        case None       => NoOpEmailOtpProvider

object NoOpEmailOtpProvider extends EmailOtpProvider:
  override def sendOtp(email: Email, code: OtpCode, template: OtpTemplate): Task[Unit] =
    ZIO.logWarning("SMTP is not configured; skipping email OTP delivery")

class SMTPOtpProvider(config: CoreConfig.SmtpConfig) extends EmailOtpProvider:

  private val session =
    val props = Properties()
    props.put("mail.smtp.host", config.host)
    props.put("mail.smtp.port", config.port.toString)
    props.put("mail.smtp.auth", "true")
    props.put("mail.smtp.starttls.enable", config.startTls.toString)
    Session.getInstance(
      props,
      new Authenticator:
        override protected def getPasswordAuthentication: PasswordAuthentication =
          PasswordAuthentication(config.username, config.password),
    )

  override def sendOtp(email: Email, code: OtpCode, template: OtpTemplate): Task[Unit] =
    ZIO.attemptBlocking:
      val message = MimeMessage(session)
      message.setFrom(InternetAddress(config.from))
      message.setRecipient(Message.RecipientType.TO, InternetAddress(email))
      message.setSubject(config.subject)
      message.setContent(render(template, code), "text/html; charset=utf-8")
      Transport.send(message)

  private def render(template: String, code: OtpCode): String =
    template.replace("{{code}}", code)
