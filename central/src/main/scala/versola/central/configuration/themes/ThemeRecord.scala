package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.theme.{ThemeOverrides, ThemePreset, ThemeTokens}
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

/** Asks what stylesheet a theme would have, for the console's preview. `tokens` present: a token theme (the foundation,
  * what the tokens set, then `css`); absent: a stylesheet theme, where `css` alone is the stylesheet. `overrides` are a
  * client's own, laid after all of that as the authorization server does.
  */
case class RenderThemeRequest(
    tokens: Option[ThemeTokens],
    css: Option[String],
    overrides: Option[ThemeOverrides],
) derives Schema, JsonCodec

case class RenderThemeResponse(
    /** The whole stylesheet: the base stylesheet, what the tokens set, the theme's own CSS, then the client's overrides. */
    css: String,
    /** Only what the theme's tokens set; empty for a stylesheet theme. */
    generated: String,
) derives Schema, JsonCodec

case class GetAllThemesResponse(
    themes: Vector[ThemeRecord],
) derives Schema, JsonCodec
