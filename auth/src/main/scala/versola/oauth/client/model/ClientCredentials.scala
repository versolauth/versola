package versola.oauth.client.model

import versola.util.Secret

sealed trait ClientCredentials:
  def clientId: ClientId

case class ClientIdWithSecret(
    clientId: ClientId,
    clientSecret: Option[Secret],
) extends ClientCredentials
