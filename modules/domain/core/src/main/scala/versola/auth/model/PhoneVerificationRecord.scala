package versola.auth.model

import versola.util.Phone
import java.time.Instant

case class PhoneVerificationRecord(
    phone: Phone,
    authId: AuthId,
    deviceId: Option[DeviceId],
    code: OtpCode,
    timesSent: Int
):
  def expireAt: Instant = authId.createdAt.plusSeconds(PhoneVerificationRecord.ttlSeconds)
    
  
object PhoneVerificationRecord:
  private val ttlSeconds: Int = 15 * 60
