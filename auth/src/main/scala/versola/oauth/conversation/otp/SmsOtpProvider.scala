package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.delivery.DeliveryChannels
import versola.util.Phone
import zio.{Task, URLayer, ZIO, ZLayer}

trait SmsOtpProvider:
  def sendOtp(phone: Phone, code: OtpCode, template: OtpTemplate): Task[Unit]

  def send(phone: Phone, message: String): Task[Unit]

object SmsOtpProvider:
  val live: URLayer[DeliveryChannels, SmsOtpProvider] =
    ZLayer.fromFunction(Impl(_))

  /** Sends through whatever SMS provider is configured when the message goes out: the one saved in central or the
    * `otp-provider` block of `auth.conf` (see [[DeliveryChannels]]), so a change applies without a restart. Nothing is
    * sent, and the reason is logged, when neither exists or the saved one is switched off.
    */
  class Impl(channels: DeliveryChannels) extends SmsOtpProvider:
    override def sendOtp(phone: Phone, code: OtpCode, template: OtpTemplate): Task[Unit] =
      channels.sms.flatMap:
        case Some(sender) => sender.send(phone, template.replace("{{code}}", code))
        case None => ZIO.logWarning("OTP provider is not configured or is switched off; skipping SMS delivery")

    override def send(phone: Phone, message: String): Task[Unit] =
      channels.sms.flatMap:
        case Some(sender) => sender.send(phone, message)
        case None => ZIO.logWarning("OTP provider is not configured or is switched off; skipping SMS delivery")
