package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class PhoneSettingsRecord(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    passwordRegex: Option[String],
) derives Schema, JsonCodec
