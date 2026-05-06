package versola.oauth.client.model

import zio.json.ast.Json
import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.schema.*

case class ClaimRecord(
    claim: Claim,
) derives Schema, JsonCodec