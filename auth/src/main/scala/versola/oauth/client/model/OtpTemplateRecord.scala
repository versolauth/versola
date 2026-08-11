package versola.oauth.client.model

import zio.json.JsonCodec

enum OtpTemplatePurpose derives JsonCodec:
  case otp, password

enum OtpTemplateChannel derives JsonCodec:
  case sms, email

case class OtpTemplateRecord(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
    purpose: OtpTemplatePurpose,
    channel: OtpTemplateChannel,
) derives JsonCodec
