package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class GetOtpTemplatesResponse(templates: Vector[OtpTemplateRecord]) derives Schema, JsonCodec

case class UpsertOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
) derives Schema, JsonCodec

case class DeleteOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
) derives Schema, JsonCodec

case class GetPhoneSettingsResponse(settings: PhoneSettingsRecord) derives Schema, JsonCodec

case class GetAllPhoneSettingsResponse(settings: Vector[PhoneSettingsRecord]) derives Schema, JsonCodec

case class UpsertPhoneSettingsRequest(
    tenantId: TenantId,
    allowedPrefixes: List[String],
) derives Schema, JsonCodec
