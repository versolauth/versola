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

/** How much the deployment the client runs in can carry. */
enum AssuranceTier derives JsonCodec, Schema, Equal:
  /** A registered key and a token bound to it. */
  case high

  /** A shared secret, for a deployment that cannot hold a key. */
  case compat

/** The combination picked in the registration wizard, stored as the client registered it.
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
    tier: AssuranceTier,
) derives Schema, JsonCodec, Equal
