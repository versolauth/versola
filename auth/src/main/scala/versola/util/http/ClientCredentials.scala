package versola.util.http

import versola.oauth.client.model.ClientId
import versola.util.Secret

sealed trait ClientCredentials

case class ClientIdWithSecret(
    clientId: ClientId,
    clientSecret: Option[Secret],
) extends ClientCredentials
