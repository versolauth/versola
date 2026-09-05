package versola.oauth.client.model

import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.*

import java.time.Instant

case class ScopeRecord(
    scope: ScopeToken,
    description: Map[String, String],
    claims: Vector[ClaimRecord],
) derives Schema, JsonCodec
