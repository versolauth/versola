package versola.user

import versola.user.model.{Login, UserId}
import versola.util.{Email, Phone}
import zio.json.JsonCodec

/** An account auth created through self-service registration, reported to central so it can
  * add the routing keys to its user index.
  */
case class UserRegisteredEvent(
    userId: UserId,
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
) derives JsonCodec
