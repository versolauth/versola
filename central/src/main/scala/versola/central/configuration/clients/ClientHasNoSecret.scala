package versola.central.configuration.clients

/** Raised when a secret operation is requested for a public (native) client. Such a client
  * is registered without a secret and can never gain one, so there is nothing to rotate or
  * to forget.
  */
case class ClientHasNoSecret(clientId: ClientId)
