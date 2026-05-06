package versola.edge.model

import versola.util.Secret

case class OAuthClient(
    id: ClientId,
    secret: Secret,
    permissions: Set[PermissionId] = Set.empty,
)
