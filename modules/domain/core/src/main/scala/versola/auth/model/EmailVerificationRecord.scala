package versola.auth.model

import versola.user.model.Email
import java.time.Instant

case class EmailVerificationRecord(
    email: Email,
    authId: AuthId,
    deviceId: Option[DeviceId],
    code: OtpCode,
    timesSent: Int
):
  def expireAt: Instant = authId.createdAt.plusSeconds(EmailVerificationRecord.ttlSeconds)
    
  
object EmailVerificationRecord:
  private val ttlSeconds: Int = 15 * 60
