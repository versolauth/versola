package versola.oauth.challenge.password.model

import zio.json.JsonCodec
import zio.schema.*

/** Channel used to deliver an admin-issued temporary password to the user. */
enum DeliveryChannel derives JsonCodec, Schema:
  case email, sms
