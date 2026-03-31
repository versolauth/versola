package versola.central.configuration.scopes

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

case class ClaimRecord(
    id: Claim,
    description: Map[String, String],
) derives Schema, Equal, CanEqual, JsonCodec
