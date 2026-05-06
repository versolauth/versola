package versola.oauth.client.model

import zio.json.ast.Json
import zio.schema.*

case class Scope(
    id: ScopeToken,
    description: Json.Obj,
    claims: List[ClaimRecord]
) derives Schema
