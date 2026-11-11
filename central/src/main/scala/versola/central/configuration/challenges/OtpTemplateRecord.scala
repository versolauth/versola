package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class OtpTemplateRecord(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
    purpose: OtpTemplatePurpose,
    channel: OtpTemplateChannel,
    /** The subject of an email, by locale. Empty means the SMTP settings' subject; a locale missing here does too. */
    subjects: Map[String, String] = Map.empty,
) derives Schema, JsonCodec
