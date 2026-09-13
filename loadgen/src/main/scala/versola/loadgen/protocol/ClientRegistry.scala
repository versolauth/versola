package versola.loadgen.protocol

/** One of the four campaign clients of design doc §2.2 as track E provisioned it: the
  * credentials and the redirect URI it was registered with.
  *
  * `ClientCreds.clientSecret` is `None` for the three `mobile-*` clients, which are public and
  * authenticate with PKCE alone -- [[HttpAuthClient]] then names the client in the request body
  * instead of sending HTTP Basic. The e2e original threw `IllegalArgumentException` from
  * `token`/`refresh` when the secret was absent, which turns a configuration fact into a defect
  * on the hot path (§3.2).
  */
case class ClientRegistration(creds: ClientCreds, redirectUri: String)

/** The clients a driver may authenticate as, keyed by `client_id`, with the one it uses when a
  * caller does not name one.
  */
case class ClientRegistry(byId: Map[String, ClientRegistration], defaultClientId: String):
  def resolve(clientId: Option[String]): Either[ProtocolError, ClientRegistration] =
    val id = clientId.getOrElse(defaultClientId)
    byId.get(id) match
      case Some(registration) => Right(registration)
      case None => Left(ProtocolError.Misconfigured("no provisioned client with client_id=" + id))
