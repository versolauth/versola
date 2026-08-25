package versola.central.configuration.clients

/** Raised when a client would end up with both `frontChannelLogoutUri` and
  * `backChannelLogoutUri` configured at the same time - only one logout
  * notification mechanism may be active per client.
  */
case class InvalidClientLogoutConfiguration(clientId: ClientId)
