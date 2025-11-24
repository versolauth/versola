package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.security.SecureRandom
import versola.util.CoreConfig
import zio.{UIO, ZIO, ZLayer}

trait OtpGenerationService:
  def generateOtpCode: UIO[OtpCode]

object OtpGenerationService:
  def live = ZLayer.fromFunction(Impl(_, _))
  
  class Impl(
      secureRandom: SecureRandom,
      config: CoreConfig,
  ) extends OtpGenerationService:
    
    override def generateOtpCode: UIO[OtpCode] =
      if config.runtime.env.isProd then
        secureRandom.nextNumeric(length = 6).map(OtpCode(_))
      else
        ZIO.succeed(OtpCode("123456"))
        
