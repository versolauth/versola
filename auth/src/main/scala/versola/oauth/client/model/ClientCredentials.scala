package versola.oauth.client.model

import versola.util.Secret

sealed trait ClientCredentials:
  def clientId: ClientId

/** RFC 7523 §2.2 `private_key_jwt`: the client authenticates with a JWT signed by a key it
  * registered, presented as `client_assertion`. Unlike a secret there is nothing here to
  * compare against a stored value -- the assertion is verified, and its `jti` checked for
  * replay, by `versola.oauth.clientauth.ClientAuthentication`.
  *
  * @param clientId read from the assertion's `sub` (RFC 7521 §4.2 makes the `client_id`
  *   parameter optional beside one), and re-checked against the signed claims during
  *   verification rather than trusted from here.
  */
case class ClientIdWithAssertion(
    clientId: ClientId,
    assertion: String,
) extends ClientCredentials

case class ClientIdWithSecret(
    clientId: ClientId,
    clientSecret: Option[Secret],
) extends ClientCredentials
