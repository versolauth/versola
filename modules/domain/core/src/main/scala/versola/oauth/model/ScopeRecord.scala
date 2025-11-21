package versola.oauth.model

import zio.schema.*

case class ScopeRecord(
                        name: ScopeToken,
                        description: ScopeDescription,
                        claims: Set[Claim],
) derives Schema
