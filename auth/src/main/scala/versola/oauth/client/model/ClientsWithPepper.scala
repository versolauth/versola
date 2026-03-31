package versola.oauth.client.model

import versola.util.Secret

case class ClientsWithPepper(
    clients: Map[ClientId, OAuthClientRecord],
    pepper: Secret,
)
