package versola.central.configuration.delivery

import jakarta.mail.{AuthenticationFailedException, SendFailedException}
import versola.util.delivery.{EmailSender, SmsProviderSettings, SmsSender, SmtpSettings}
import versola.util.{Email, Phone, Secret}
import zio.http.{Client, Method, URL}
import zio.json.JsonDecoder
import zio.{Chunk, Clock, Ref, Task, URLayer, ZIO, ZLayer, durationInt}

import java.net.{ConnectException, NoRouteToHostException, SocketTimeoutException, UnknownHostException}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.concurrent.TimeoutException

/** Sends one message through the settings an administrator is editing, so a wrong server, account or address is found
  * before it is saved and before a user waits for a code that never comes. It works in every environment: unlike a code
  * sent by auth, nothing here is a user's, and the administrator asked for it.
  *
  * It sends from central, the one place that can do it for settings that are not saved yet. The senders are the ones auth
  * uses, from `util`, so what works here is what auth will do.
  *
  * What it never does: return what the other side said (a provider's answer body or an SMTP server's text is not
  * central's to repeat), accept a password for any server but the one it was saved for, or log a password or a recipient.
  */
trait DeliveryTestService:
  def send(request: DeliveryTestService.Request): Task[Either[DeliveryTestService.Refused, DeliveryTestService.Outcome]]

object DeliveryTestService:

  /** `channel` is `email` or `sms`. The block under test travels with the request, so unsaved edits are what is tried.
    * `password` is the one typed for it; left out, the saved one is used, for the server it was saved for only.
    */
  case class Request(
      channel: String,
      to: String,
      smtp: Option[SmtpDeliverySettings],
      otpProvider: Option[OtpProviderDeliverySettings],
      password: Option[String],
  ) derives JsonDecoder: // No encoder on purpose: the type holds a plaintext password.
    override def toString: String =
      s"Request(channel=$channel, smtp=$smtp, otpProvider=$otpProvider, password=${password.fold("none")(_ => "***")})"

  /** The request cannot be tried at all; nothing was sent. The message names the field and the rule, never a value.
    * `rateLimited` is the one refusal that is about how often, not about what: it answers `429`, not `400`. */
  case class Refused(message: String, rateLimited: Boolean = false)

  /** At most this many messages leave central in [[Window]]. An administrator checking a setting needs a few; the limit
    * is what keeps the endpoint from being a way to mail or text a victim at the operator's cost. */
  val Limit = 5
  val Window: Duration = Duration.ofMinutes(1)

  /** What came of the attempt. A failed attempt is an answer, not an error of this call: the administrator asked whether
    * delivery works, and the answer is no. `code` is a closed set a console can act on.
    */
  enum Outcome:
    case Delivered
    case Failed(code: String, message: String)

  val TestMessage = "This is a test message from Versola. If you received it, delivery is configured correctly."

  val live: URLayer[DeliverySettingsService & Client, DeliveryTestService] =
    ZLayer.fromZIO:
      for
        settings <- ZIO.service[DeliverySettingsService]
        client <- ZIO.service[Client]
        attempts <- Ref.make(Chunk.empty[Instant])
      yield Impl(settingsService = settings, client = client, attempts = attempts)

  class Impl(settingsService: DeliverySettingsService, client: Client, attempts: Ref[Chunk[Instant]]) extends DeliveryTestService:

    override def send(request: Request): Task[Either[Refused, Outcome]] =
      request.channel match
        case "email" => email(request)
        case "sms" => sms(request)
        case _ => refuse("channel must be email or sms")

    private def email(request: Request): Task[Either[Refused, Outcome]] =
      request.smtp match
        case None => refuse("smtp is required to test the email channel")
        case Some(block) =>
          SmtpDeliverySettings.validate(block) match
            case Some(reason) => refuse(reason)
            case None =>
              Email.from(request.to) match
                case Left(_) => refuse("to must be an email address")
                case Right(to) =>
                  savedSmtpPassword(block, request.password).flatMap:
                    case Left(refused) => ZIO.left(refused)
                    case Right(password) =>
                      val settings = SmtpSettings(
                        host = block.host.trim,
                        port = block.port,
                        username = block.username,
                        password = password,
                        from = Email(block.from),
                        subject = block.subject,
                        startTls = block.startTls,
                      )
                      attempt(EmailSender.smtp(settings).send(to, s"<p>$TestMessage</p>"))

    private def sms(request: Request): Task[Either[Refused, Outcome]] =
      request.otpProvider match
        case None => refuse("otpProvider is required to test the SMS channel")
        case Some(block) =>
          OtpProviderDeliverySettings.validate(block) match
            case Some(reason) => refuse(reason)
            case None =>
              Phone.parse(request.to) match
                case Left(_) => refuse("to must be a phone number")
                case Right(to) =>
                  URL.decode(block.url) match
                    case Left(_) => refuse("otpProvider.url must be an absolute HTTP(S) URL")
                    case Right(url) =>
                      savedSmsPassword(block, request.password).flatMap:
                        case Left(refused) => ZIO.left(refused)
                        case Right(password) =>
                          val settings = SmsProviderSettings(
                            method = Method.fromString(block.method),
                            url = url,
                            username = block.username,
                            password = password,
                            body = block.body,
                          )
                          attempt(SmsSender.http(client, settings).send(to, TestMessage))

    /** The typed password, else the saved one if it belongs to this server, else none. A saved password for a different
      * server is a refusal, not a silent fallback to none: the administrator must say what to send, and to whom.
      */
    private def savedSmtpPassword(block: SmtpDeliverySettings, typed: Option[String]): Task[Either[Refused, Option[String]]] =
      typed.filter(_.nonEmpty) match
        case Some(password) => ZIO.right(Some(password))
        case None =>
          settingsService.getSettings.map: record =>
            record.flatMap(r => r.smtp.zip(r.smtpPassword)) match
              case Some((saved, password)) if SmtpDeliverySettings.sameServer(saved, block) => Right(Some(utf8(password)))
              case Some(_) => Left(Refused("password must be entered again when the server address, port or username changes"))
              case None => Right(None)

    private def savedSmsPassword(block: OtpProviderDeliverySettings, typed: Option[String]): Task[Either[Refused, Option[String]]] =
      typed.filter(_.nonEmpty) match
        case Some(password) => ZIO.right(Some(password))
        case None =>
          settingsService.getSettings.map: record =>
            record.flatMap(r => r.otpProvider.zip(r.otpProviderPassword)) match
              case Some((saved, password)) if OtpProviderDeliverySettings.sameEndpoint(saved, block) => Right(Some(utf8(password)))
              case Some(_) => Left(Refused("password must be entered again when the address, its host or port, or the username changes"))
              case None => Right(None)

    private def utf8(secret: Secret): String = String(secret, StandardCharsets.UTF_8)

    /** Counts the attempt before it is made, so a slow or failing server cannot be used to get around the limit. */
    private def attempt(send: Task[Unit]): Task[Either[Refused, Outcome]] =
      Clock.instant.flatMap: now =>
        attempts
          .modify: previous =>
            val recent = previous.filter(_.isAfter(now.minus(Window)))
            if recent.length >= Limit then (false, recent) else (true, recent :+ now)
          .flatMap:
            case false =>
              ZIO.left(Refused(message = "too many test messages, try again in a minute", rateLimited = true))
            case true =>
              send.fold(error => Right(failed(error)), _ => Right(Outcome.Delivered))

    private def refuse(message: String): Task[Either[Refused, Outcome]] = ZIO.left(Refused(message))

  /** What went wrong, in words that say what to look at and never repeat what the other side answered. */
  def failed(error: Throwable): Outcome.Failed =
    val causes = Iterator
      .iterate(Option(error))(_.flatMap(t => Option(t.getCause).filterNot(_.eq(t))))
      .take(16)
      .takeWhile(_.isDefined)
      .flatten
      .toList
    val providerStatus = causes.collectFirst:
      case rejected: SmsSender.ProviderRejected => rejected.status
    if causes.exists(_.isInstanceOf[AuthenticationFailedException]) then
      Outcome.Failed(code = "authentication_failed", message = "The server refused the username or the password.")
    else if causes.exists(_.isInstanceOf[UnknownHostException]) then
      Outcome.Failed(code = "unknown_host", message = "The address could not be resolved.")
    else if causes.exists(c => c.isInstanceOf[SocketTimeoutException] || c.isInstanceOf[TimeoutException]) then
      Outcome.Failed(code = "timeout", message = "The server did not answer in time.")
    else if causes.exists(c => c.isInstanceOf[ConnectException] || c.isInstanceOf[NoRouteToHostException]) then
      Outcome.Failed(code = "connection_failed", message = "The server could not be reached at that address and port.")
    else if causes.exists(_.isInstanceOf[SendFailedException]) then
      Outcome.Failed(code = "rejected", message = "The server refused the sender or the recipient address.")
    else
      providerStatus match
        case Some(status) => Outcome.Failed(code = "provider_status", message = s"The provider answered with status $status.")
        case _ => Outcome.Failed(code = "send_failed", message = "The message could not be sent.")
