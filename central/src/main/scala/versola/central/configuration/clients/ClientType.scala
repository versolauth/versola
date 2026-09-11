package versola.central.configuration.clients

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** How a client is able to hold credentials, chosen once at registration.
  *
  * A `web` client is confidential and is issued a secret; a `native` client is public and
  * never gets one, because a secret shipped inside an app or a browser bundle is not a
  * secret. The distinction is stored implicitly - a record with no `secret` is native - so
  * a client cannot move between the two afterwards.
  */
enum ClientType derives JsonCodec, Schema, Equal:
  case web, native
