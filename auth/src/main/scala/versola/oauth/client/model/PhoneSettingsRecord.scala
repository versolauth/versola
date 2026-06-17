package versola.oauth.client.model

import zio.json.JsonCodec

case class PhoneSettingsRecord(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    passwordRegex: Option[String],
) derives JsonCodec
