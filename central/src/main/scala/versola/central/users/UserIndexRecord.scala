package versola.central.users

import versola.util.{Email, Phone}
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.{Schema, derived}

given JsonCodec[Email] = JsonCodec.string.transform(Email(_), identity)
given JsonCodec[Phone] = JsonCodec.string.transform(Phone(_), identity)

case class UserIndexRecord(
    id: UserId,
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
) derives JsonCodec, Schema
