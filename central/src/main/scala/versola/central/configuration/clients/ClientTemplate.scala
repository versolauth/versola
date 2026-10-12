package versola.central.configuration.clients

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** What the client is, in the terms the person registering it chose it by. Held apart from
  * [[AuthMethod]] and the rest of the settings it decided: those say what the client does
  * now, and a client is edited after it is registered. */
enum ClientKind derives JsonCodec, Schema, Equal:
  /** A server-side web application, which keeps a credential of its own. */
  case web

  /** A mobile or desktop binary, which cannot keep one. */
  case device

  /** A service calling on its own behalf, with no user to sign in. */
  case service

/** What the registration wizard was told is being built, stored as the client registered it.
  *
  * The security tier that used to sit beside it is gone: how strict a client is held follows
  * from the tenant's [[SecurityProfile]], not from a choice made per client.
  *
  * Kept rather than derived back out of the settings it applied, because the settings are
  * what an operator edits: once a TTL or a PAR requirement has been changed, several
  * combinations fit the row equally well, and the one a diff is shown against would change
  * under the operator as they edit. A stored template makes "differs from the template" a
  * fact about the registration, not a guess about the current state.
  *
  * `None` for every client registered before the column existed, and for any registered
  * through the API without naming one -- neither has a template to differ from. */
case class ClientTemplate(
    kind: ClientKind,
) derives Schema, JsonCodec, Equal

object ClientKind:
  /** The kind a stored client is, for the typed registration endpoints to hold an update to.
    *
    * The template names it when the client was registered with one. Otherwise it is read off
    * what the client is: a native one is a device, one with a redirect URI or a sign-in flow
    * has a user to sign in, and what is left calls on its own behalf. */
  def of(client: OAuthClientRecord): ClientKind =
    client.template.map(_.kind).getOrElse:
      if client.applicationType == ApplicationType.native then ClientKind.device
      else if client.authFlow.nonEmpty || client.redirectUris.nonEmpty then ClientKind.web
      else ClientKind.service
