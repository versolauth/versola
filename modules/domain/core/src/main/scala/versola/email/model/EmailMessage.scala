package versola.email.model

import versola.user.model.Email

case class EmailMessage(
    to: Email,
    from: EmailAddress,
    subject: String,
    textBody: Option[String] = None,
    htmlBody: Option[String] = None,
    replyTo: Option[EmailAddress] = None,
)

object EmailMessage:
  def text(
      to: Email,
      from: EmailAddress,
      subject: String,
      body: String,
  ): EmailMessage =
    EmailMessage(
      to = to,
      from = from,
      subject = subject,
      textBody = Some(body),
    )

  def html(
      to: Email,
      from: EmailAddress,
      subject: String,
      body: String,
  ): EmailMessage =
    EmailMessage(
      to = to,
      from = from,
      subject = subject,
      htmlBody = Some(body),
    )

  def multipart(
      to: Email,
      from: EmailAddress,
      subject: String,
      textBody: String,
      htmlBody: String,
  ): EmailMessage =
    EmailMessage(
      to = to,
      from = from,
      subject = subject,
      textBody = Some(textBody),
      htmlBody = Some(htmlBody),
    )
