package versola.oauth.client.model

import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.*

import java.time.Instant

case class ScopeRecord(
    scope: ScopeToken,
    claims: Vector[ClaimRecord],
) derives Schema, JsonCodec
