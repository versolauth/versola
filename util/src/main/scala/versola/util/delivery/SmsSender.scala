package versola.util.delivery

import versola.util.Phone
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Task, ZIO, durationInt}

import java.util.concurrent.TimeoutException

/** Sends one text message to one phone number. */
trait SmsSender:
  def send(to: Phone, message: String): Task[Unit]

object SmsSender:
  def http(client: Client, settings: SmsProviderSettings): SmsSender = HttpSmsSender(client, settings)

  /** The provider answered with a status that is not a success. Only the status is kept: the body is the provider's and
    * may hold anything. */
  final class ProviderRejected(val status: Int) extends RuntimeException(s"OTP provider responded with $status")

/** Delivery through a generic HTTP service: one request per message, whose JSON body is the configured entries with
  * `{{phone}}` and `{{message}}` replaced. A response that is not a success fails the send; its body is never read into
  * the error, because it is the provider's and may hold anything.
  */
final class HttpSmsSender(client: Client, settings: SmsProviderSettings) extends SmsSender:

  override def send(to: Phone, message: String): Task[Unit] =
    val fields = settings.body.map((key, value) => key -> render(value, to, message))
    val body = Json.Obj(fields.map((key, value) => key -> Json.Str(value)).toSeq*)
    val base = Request(
      method = settings.method,
      url = settings.url,
      body = Body.fromString(body.toJson),
    ).addHeader(Header.ContentType(MediaType.application.json))
    val request = (settings.username, settings.password) match
      case (Some(username), Some(password)) => base.addHeader(Header.Authorization.Basic(username, password))
      case _ => base
    ZIO
      .scoped(client.request(request))
      .timeoutFail(TimeoutException("the provider did not answer in time"))(HttpSmsSender.Timeout)
      .flatMap: response =>
        ZIO
          .fail(SmsSender.ProviderRejected(response.status.code))
          .when(!response.status.isSuccess)
          .unit

  private def render(value: String, phone: Phone, message: String): String =
    value
      .replace("{{phone}}", phone)
      .replace("{{message}}", message)

object HttpSmsSender:
  /** How long one request may take. Without it a provider that accepts the connection and never answers holds the call,
    * and whoever waits on it (a login, a password reset, an administrator's test), for as long as the pool lets it. */
  val Timeout = 10.seconds
