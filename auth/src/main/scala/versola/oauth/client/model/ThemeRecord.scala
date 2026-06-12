package versola.oauth.client.model

import zio.json.JsonCodec

case class ThemeRecord(
    id: String,
    css: String,
    tenantId: Option[TenantId],
) derives JsonCodec
