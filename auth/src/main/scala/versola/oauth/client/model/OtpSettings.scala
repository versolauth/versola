package versola.oauth.client.model

case class OtpSettings(length: Int, resendAfter: Int)

object OtpSettings:
  val default: OtpSettings = OtpSettings(length = 6, resendAfter = 60)
