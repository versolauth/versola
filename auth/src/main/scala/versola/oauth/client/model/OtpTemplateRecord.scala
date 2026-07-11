package versola.oauth.client.model

import zio.json.JsonCodec

case class OtpTemplateRecord(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
    purpose: String,
) derives JsonCodec
