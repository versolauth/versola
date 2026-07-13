package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.{CoreConfig, Phone}
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

trait SmsOtpProvider:
  def sendOtp(phone: Phone, code: OtpCode, template: OtpTemplate): Task[Unit]

  def send(phone: Phone, message: String): Task[Unit]

object SmsOtpProvider:
  val live: URLayer[Client & CoreConfig, SmsOtpProvider] =
    ZLayer.fromFunction: (client: Client, config: CoreConfig) =>
      config.otpProvider match
        case Some(otpConfig) => GenericHttpSmsOtpProvider(client, otpConfig)
        case None            => NoOpSmsOtpProvider

object NoOpSmsOtpProvider extends SmsOtpProvider:
  override def sendOtp(phone: Phone, code: OtpCode, template: OtpTemplate): Task[Unit] =
    ZIO.logWarning("OTP provider is not configured; skipping SMS OTP delivery")

  override def send(phone: Phone, message: String): Task[Unit] =
    ZIO.logWarning("OTP provider is not configured; skipping SMS delivery")

class GenericHttpSmsOtpProvider(
    httpClient: Client,
    otpConfig: CoreConfig.OtpProvider,
) extends SmsOtpProvider:

  override def sendOtp(phone: Phone, code: OtpCode, template: OtpTemplate): Task[Unit] =
    send(phone, template.replace("{{code}}", code))

  override def send(phone: Phone, message: String): Task[Unit] =
    val fields = otpConfig.body.map((key, value) => key -> render(value, phone, message))
    val body = Json.Obj(fields.map((key, value) => key -> Json.Str(value)).toSeq*)
    val baseRequest = Request(
      method = otpConfig.method,
      url = otpConfig.url,
      body = Body.fromString(body.toJson),
    ).addHeader(Header.ContentType(MediaType.application.json))
    val request = (otpConfig.username, otpConfig.password) match
      case (Some(username), Some(password)) =>
        baseRequest.addHeader(Header.Authorization.Basic(username, password))
      case _ =>
        baseRequest
    ZIO.scoped(httpClient.request(request)).flatMap: response =>
      ZIO
        .fail(RuntimeException(s"OTP provider responded with ${response.status.code}"))
        .when(!response.status.isSuccess)
        .unit

  private def render(value: String, phone: Phone, message: String): String =
    value
      .replace("{{phone}}", phone)
      .replace("{{message}}", message)
