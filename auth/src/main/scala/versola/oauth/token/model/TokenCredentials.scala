package versola.oauth.token.model

import versola.oauth.client.model.{ClientId, ClientSecret}
import versola.security.Secret

sealed trait TokenCredentials

case class ClientIdWithSecret(
    clientId: ClientId,
    clientSecret: Option[Secret],
) extends TokenCredentials
