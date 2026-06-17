package versola.central.configuration.locales

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class LocaleRecord(
    code: String,
    name: String,
    isDefault: Boolean,
    active: Boolean,
) derives Schema, JsonCodec
