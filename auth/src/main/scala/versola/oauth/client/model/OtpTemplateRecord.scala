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
    /** The subject of an email, by locale; absent from an older central, which then leaves the SMTP settings' subject. */
    subjects: Map[String, String] = Map.empty,
) derives JsonCodec
