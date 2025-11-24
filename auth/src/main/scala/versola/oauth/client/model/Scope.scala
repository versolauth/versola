package versola.oauth.client.model

import zio.schema.*

case class Scope(
    claims: Set[Claim],
    description: ScopeDescription,
) derives Schema
