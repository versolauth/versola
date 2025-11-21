package versola.email.impl

import jakarta.mail.*
import jakarta.mail.internet.{InternetAddress, MimeBodyPart, MimeMessage, MimeMultipart}
import versola.email.EmailService
import versola.email.config.SmtpConfig
import versola.email.model.{EmailAddress, EmailMessage}
import zio.*

import java.util.Properties

class SmtpEmailService(config: SmtpConfig) extends EmailService:

  override def sendEmail(message: EmailMessage): Task[Unit] =
    ZIO.attempt {
      val session = createSession()
      val mimeMessage = createMimeMessage(session, message)
      Transport.send(mimeMessage)
    }.tapError(error =>
      ZIO.logError(s"Failed to send email to ${message.to}: ${error.getMessage}"),
    ).tapBoth(
      error => ZIO.logError(s"Email sending failed: ${error.getMessage}"),
      _ => ZIO.logInfo(s"Email sent successfully to ${message.to}"),
    )

  private def createSession(): Session =
    val props = new Properties()

    // Basic SMTP configuration
    props.put("mail.smtp.host", config.host)
    props.put("mail.smtp.port", config.port.toString)
    props.put("mail.smtp.auth", "true")

    // Timeout configuration
    props.put(
      "mail.smtp.connectiontimeout",
      config.connectionTimeout.getOrElse(10000).toString,
    )
    props.put(
      "mail.smtp.timeout",
      config.timeout.getOrElse(10000).toString,
    )

    if config.useTls.getOrElse(true) then
      props.put("mail.smtp.ssl.enable", "true")
      props.put("mail.smtp.ssl.protocols", "TLSv1.2")

    if config.useStartTls.getOrElse(true) then
      props.put("mail.smtp.starttls.enable", "true")
      props.put("mail.smtp.starttls.required", "true")

    // Create authenticator
    val authenticator = new Authenticator:
      override def getPasswordAuthentication: PasswordAuthentication =
        new PasswordAuthentication(config.username, config.password)

    Session.getInstance(props, authenticator)

  private def createMimeMessage(session: Session, message: EmailMessage): MimeMessage =
    val mimeMessage = new MimeMessage(session)

    // Set addresses
    val fromAddress = new InternetAddress(config.fromAddress, config.fromName.orNull)
    mimeMessage.setFrom(fromAddress)
    mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress(message.to))

    message.replyTo.foreach { replyTo =>
      mimeMessage.setReplyTo(Array(new InternetAddress(replyTo)))
    }

    // Set subject
    mimeMessage.setSubject(message.subject, "UTF-8")

    // Set content based on message type
    (message.textBody, message.htmlBody) match
      case (Some(text), Some(html)) =>
        // Multipart message
        val multipart = new MimeMultipart("alternative")

        val textPart = new MimeBodyPart()
        textPart.setText(text, "UTF-8")
        multipart.addBodyPart(textPart)

        val htmlPart = new MimeBodyPart()
        htmlPart.setContent(html, "text/html; charset=UTF-8")
        multipart.addBodyPart(htmlPart)

        mimeMessage.setContent(multipart)

      case (Some(text), None) =>
        // Text only
        mimeMessage.setText(text, "UTF-8")

      case (None, Some(html)) =>
        // HTML only
        mimeMessage.setContent(html, "text/html; charset=UTF-8")

      case (None, None) =>
        throw new IllegalArgumentException("Email message must have either text or HTML body")

    mimeMessage

object SmtpEmailService:
  def layer(config: SmtpConfig): ULayer[EmailService] =
    ZLayer.succeed(SmtpEmailService(config))
