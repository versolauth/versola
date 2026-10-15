package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.theme.{ThemePreset, ThemeTokens}
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** A theme is a stylesheet, or -- when `tokens` is set -- a built-in base theme with overrides, in which case `css`
  * holds only the theme's own additions and may be empty.
  */
case class ThemeRecord(
    id: String,
    css: String,
    tenantId: Option[TenantId],
    tokens: Option[ThemeTokens],
) derives Schema, JsonCodec

case class CreateThemeRequest(
    id: String,
    css: String,
    tenantId: Option[TenantId],
    tokens: Option[ThemeTokens],
) derives Schema, JsonCodec

case class UpdateThemeRequest(
    id: String,
    css: String,
    tokens: Option[ThemeTokens],
) derives Schema, JsonCodec

case class GetThemePresetsResponse(
    presets: Vector[ThemePreset],
) derives Schema, JsonCodec

/** Asks what stylesheet a theme built from these tokens would have, for the console's preview. */
case class RenderThemeRequest(
    tokens: ThemeTokens,
    css: Option[String],
) derives Schema, JsonCodec

case class RenderThemeResponse(
    /** The whole stylesheet: the current default, what the tokens set, then the theme's own CSS. */
    css: String,
    /** Only what the tokens set. */
    generated: String,
) derives Schema, JsonCodec

case class GetAllThemesResponse(
    themes: Vector[ThemeRecord],
) derives Schema, JsonCodec
