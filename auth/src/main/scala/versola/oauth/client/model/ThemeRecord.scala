package versola.oauth.client.model

import versola.util.theme.ThemeTokens
import zio.json.JsonCodec

case class ThemeRecord(
    id: String,
    css: String,
    tenantId: Option[TenantId],
    // Set when the theme is a built-in base with overrides; `css` is then only the theme's own additions.
    tokens: Option[ThemeTokens],
) derives JsonCodec
