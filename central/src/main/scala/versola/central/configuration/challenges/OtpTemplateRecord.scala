package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class OtpTemplateRecord(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
    purpose: String,
) derives Schema, JsonCodec
