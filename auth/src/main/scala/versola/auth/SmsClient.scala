package versola.auth

import versola.auth.model.OtpCode
import versola.util.Phone
import zio.*
import zio.http.*

trait SmsClient:
  def sendSms(phone: Phone, code: OtpCode): Task[Unit]

object SmsClient:
  private def template(code: OtpCode): String =
    s"Dvor код подтверждения - $code."

  def live: ZLayer[Client, Nothing, SmsClient] =
    ZLayer.fromFunction(Impl(_))

  class Impl(
      //smsConfig: SmsConfig,
      client: Client,
  ) extends SmsClient:

    override def sendSms(phone: Phone, code: OtpCode): Task[Unit] =
      ZIO.unit
      /*val request = Request
        .post(
          url = smsConfig.url.addPath(Path.root / "sys" / "send.php"),
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "charset" -> "utf-8",
              "login" -> smsConfig.login,
              "psw" -> smsConfig.password,
              "sender" -> "Dvor",
              "fmt" -> "3",
              "phones" -> phone,
              "mes" -> template(code),
            ),
          ),
        ).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))

      for
        response <- client.batched(request)
          .timeout(10.seconds)
          .someOrElse(Response.serviceUnavailable("Timeout 10 seconds occured"))
          .catchNonFatalOrDie(ex => ZIO.succeed(Response.serviceUnavailable(ex.getMessage)))

        _ <- ZIO.logInfo(s"SMS sent to $phone with response status: ${response.status}")
      yield ()*/
