package versola.email.impl

import versola.email.EmailService
import versola.email.config.MailgunConfig
import versola.email.model.EmailMessage
import zio.*
import zio.http.*
import zio.json.*

import java.util.Base64

class MailgunEmailService(rawConfig: MailgunConfig, client: Client) extends EmailService:
  private val config = rawConfig.withDefaults

  override def sendEmail(message: EmailMessage): Task[Unit] =
    for
      request <- createMailgunRequest(message)
      response <- client.batched(request)
        .timeout(30.seconds)
        .someOrElse(Response.serviceUnavailable("Mailgun API timeout"))
        .catchNonFatalOrDie(ex => ZIO.succeed(Response.serviceUnavailable(ex.getMessage)))
      _ <- handleResponse(response, message)
    yield ()

  private def createMailgunRequest(message: EmailMessage): Task[Request] =
    ZIO.attempt {
      val url = URL.decode(s"${config.baseUrl.get}/${config.domain}/messages").toOption
        .getOrElse(throw new IllegalArgumentException("Invalid Mailgun base URL"))

      val fromField = config.fromName match
        case Some(name) => s"$name <${config.fromAddress}>"
        case None => config.fromAddress.toString

      val baseFields = Seq(
        "from" -> fromField,
        "to" -> message.to.toString,
        "subject" -> message.subject,
      )

      val optionalFields =
        message.textBody.map("text" -> _).toSeq ++
        message.htmlBody.map("html" -> _).toSeq ++
        message.replyTo.map(replyTo => "h:Reply-To" -> replyTo.toString).toSeq

      val formData = Form.fromStrings((baseFields ++ optionalFields)*)

      val authHeader = createAuthHeader()

      Request
        .post(url, Body.fromURLEncodedForm(formData))
        .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
        .addHeader(authHeader)
    }

  private def createAuthHeader(): Header =
    Header.Authorization.Basic("api", config.apiKey)

  private def handleResponse(response: Response, message: EmailMessage): Task[Unit] =
    response.status match
      case Status.Ok =>
        for
          body <- response.body.asString
          _ <- ZIO.logInfo(s"Email sent successfully to ${message.to} via Mailgun")
          _ <- ZIO.logDebug(s"Mailgun response: $body")
        yield ()
        
      case status =>
        for
          body <- response.body.asString.orElse(ZIO.succeed("No response body"))
          error = s"Mailgun API error: $status - $body"
          _ <- ZIO.logError(s"Failed to send email to ${message.to}: $error")
          _ <- ZIO.fail(new RuntimeException(error))
        yield ()

object MailgunEmailService:
  def layer(config: MailgunConfig): ZLayer[Client, Nothing, EmailService] =
    ZLayer.fromFunction(MailgunEmailService(config, _))
