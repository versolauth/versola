package versola.oauth.metadata

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.{Schema, derived}

case class ServerMetadataRecord(
    id: String,
    metadata: Json.Obj,
) derives Schema, JsonCodec
