package versola.oauth.model

import zio.schema.*

case class Scope(
    claims: Set[Claim],
    description: ScopeDescription,
) derives Schema
