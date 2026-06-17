package versola.central.configuration.locales

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class GetLocalesResponse(
    locales: Vector[LocaleRecord],
) derives Schema, JsonCodec

case class UpdateLocalesRequest(
    add: Vector[LocaleRecord],
    delete: Vector[String],
) derives Schema, JsonCodec

case class SetDefaultLocaleRequest(
    code: String,
) derives Schema, JsonCodec

case class SyncLocaleRecord(
    code: String,
    name: String,
) derives Schema, JsonCodec

case class GetLocalesSyncResponse(
    locales: Vector[SyncLocaleRecord],
    default: String,
) derives Schema, JsonCodec
