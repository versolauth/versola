package versola.util.delivery

import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import versola.util.Email
import zio.{Task, ZIO}

import java.util.Properties

/** Sends one HTML message to one address through a mail server. `subject` is the message's own; without one the server
  * settings' subject is used, which is what every message had before a template could carry a subject.
  */
trait EmailSender:
  def send(to: Email, subject: Option[String], message: String): Task[Unit]

object EmailSender:
  def smtp(settings: SmtpSettings): EmailSender = SmtpEmailSender(settings)

/** Delivery through an SMTP server. The session is built once, so a sender is made when its settings change, not per message.
  *
  * The three timeouts bound a server that accepts the connection and then says nothing: without them the blocked call
  * never returns and holds its thread.
  */
final class SmtpEmailSender(settings: SmtpSettings) extends EmailSender:
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
    Session.getInstance(
      props,
      new Authenticator:
        override protected def getPasswordAuthentication: PasswordAuthentication =
          PasswordAuthentication(settings.username, settings.password.getOrElse("")),
    )

  override def send(to: Email, subject: Option[String], message: String): Task[Unit] =
    ZIO.attemptBlocking:
      val mail = MimeMessage(session)
      mail.setFrom(InternetAddress(settings.from))
      mail.setRecipient(Message.RecipientType.TO, InternetAddress(to))
      // Named, not left to the platform default: a subject in the user's language is not ASCII.
      mail.setSubject(SmtpEmailSender.oneLine(subject.getOrElse(settings.subject)), "UTF-8")
      mail.setContent(message, "text/html; charset=utf-8")
      Transport.send(mail)

object SmtpEmailSender:
  /** A subject is a header: a line break in it would end the header and start another. Central refuses one, but a subject
    * is also filled with a client's name, so the sender does not rely on that.
    */
  private[delivery] def oneLine(subject: String): String =
    subject.map(c => if c.isControl then ' ' else c)

  private val TimeoutMillis = "10000"
