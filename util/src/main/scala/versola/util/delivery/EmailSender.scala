package versola.util.delivery

import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import versola.util.Email
import zio.{Task, ZIO}

import java.util.Properties

/** Sends one HTML message to one address through a mail server. */
trait EmailSender:
  def send(to: Email, message: String): Task[Unit]

object EmailSender:
  def smtp(settings: SmtpSettings): EmailSender = SmtpEmailSender(settings, None)

  /** The server comes from the admin API, so each connection is checked against `policy` ([[DeliveryTargetPolicy]]). */
  def smtp(settings: SmtpSettings, policy: DeliveryTargetPolicy): EmailSender = SmtpEmailSender(settings, Some(policy))

/** Delivery through an SMTP server. The session is built once, so a sender is made when its settings change, not per message.
  *
  * The three timeouts bound a server that accepts the connection and then says nothing: without them the blocked call
  * never returns and holds its thread.
  */
final class SmtpEmailSender(settings: SmtpSettings, policy: Option[DeliveryTargetPolicy]) extends EmailSender:
  private val session =
    val props = Properties()
    props.put("mail.smtp.host", settings.host)
    props.put("mail.smtp.port", settings.port.toString)
    props.put("mail.smtp.auth", settings.password.isDefined.toString)
    props.put("mail.smtp.starttls.enable", settings.startTls.toString)
    // STARTTLS that is merely enabled is an offer: a server that does not make it, or a peer on the route that strips it
    // from the greeting, gets the password and the message in clear. Required, the session fails instead. The server's
    // certificate is checked against its host name as any TLS client does; Jakarta Mail leaves both off by default.
    props.put("mail.smtp.starttls.required", settings.startTls.toString)
    props.put("mail.smtp.ssl.checkserveridentity", "true")
    props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
    props.put("mail.smtp.connectiontimeout", SmtpEmailSender.TimeoutMillis)
    props.put("mail.smtp.timeout", SmtpEmailSender.TimeoutMillis)
    props.put("mail.smtp.writetimeout", SmtpEmailSender.TimeoutMillis)
    // Checked where the connection is made, on the address it goes to, not on a lookup made beforehand.
    // Without `fallback=false` a refused connection is retried on a plain socket, which is the check undone.
    policy.foreach: rules =>
      props.put("mail.smtp.socketFactory", GuardedSocketFactory(rules))
      props.put("mail.smtp.socketFactory.fallback", "false")
    Session.getInstance(
      props,
      new Authenticator:
        override protected def getPasswordAuthentication: PasswordAuthentication =
          PasswordAuthentication(settings.username, settings.password.getOrElse("")),
    )

  override def send(to: Email, message: String): Task[Unit] =
    ZIO.attemptBlocking:
      val mail = MimeMessage(session)
      mail.setFrom(InternetAddress(settings.from))
      mail.setRecipient(Message.RecipientType.TO, InternetAddress(to))
      mail.setSubject(settings.subject)
      mail.setContent(message, "text/html; charset=utf-8")
      Transport.send(mail)

object SmtpEmailSender:
  private val TimeoutMillis = "10000"
