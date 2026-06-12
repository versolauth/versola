package versola.oauth.client.model

import zio.json.JsonCodec

case class LocaleRecord(
    code: String,
    name: String,
) derives JsonCodec

case class Locales(
    locales: Vector[LocaleRecord],
    default: String,
) derives JsonCodec
