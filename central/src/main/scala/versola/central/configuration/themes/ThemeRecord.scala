package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class ThemeRecord(
    id: String,
    css: String,
    tenantId: Option[TenantId],
) derives Schema, JsonCodec

case class CreateThemeRequest(
    id: String,
    css: String,
    tenantId: Option[TenantId],
) derives Schema, JsonCodec

case class UpdateThemeRequest(
    id: String,
    css: String,
) derives Schema, JsonCodec

case class GetAllThemesResponse(
    themes: Vector[ThemeRecord],
) derives Schema, JsonCodec
