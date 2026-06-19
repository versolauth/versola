package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.util.{EnvName, SecureRandom}
import zio.{UIO, ZIO, ZLayer}

trait OtpGenerationService:
  def generateOtpCode(length: Int): UIO[OtpCode]

object OtpGenerationService:
  def live = ZLayer.fromFunction(Impl(_, _))

  class Impl(
      secureRandom: SecureRandom,
      env: EnvName,
  ) extends OtpGenerationService:

    override def generateOtpCode(length: Int): UIO[OtpCode] =
      if env.isProd then
        secureRandom.nextNumeric(length).map(OtpCode(_))
      else
        ZIO.succeed(OtpCode(("1234567890" * (length / 10 + 1)).take(length)))
