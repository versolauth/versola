package versola.central.configuration.clients

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** OpenID Connect Dynamic Client Registration §2 `application_type`: what kind of program the
  * client is, which is not the same question as how it authenticates.
  *
  * The two used to be read off each other -- a native app was a client with
  * [[AuthMethod.none]] -- and that is exactly what an app fronted by edge breaks (#421): the
  * app is native (its redirect is an App Link, its DPoP key lives on the device), while edge
  * authenticates as the client with `tls_client_auth`. Stating the type separately is what
  * lets a registration be native and confidential at once, and lets auth tell that client's
  * certificate apart from one whose tokens should be bound to it.
  */
enum ApplicationType derives JsonCodec, Schema, Equal, CanEqual:
  /** A server-side or browser application. The default, and what every client registered
    * before the field existed is. */
  case web

  /** A mobile or desktop binary running on the user's device. */
  case native
