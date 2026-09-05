package versola.oauth.challenge.password.model

import zio.json.JsonCodec
import zio.schema.*

/** Channel used to deliver an admin-issued temporary password to the user.
  * `show` returns the plaintext to the calling admin instead of delivering it,
  * and is rejected in production.
  */
enum DeliveryChannel derives JsonCodec, Schema:
  case email, sms, show
