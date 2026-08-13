package versola.user

import versola.user.model.{Login, UserId}
import versola.util.{Email, Phone}
import zio.json.JsonCodec

/** An account auth created through self-service registration, reported to central so it can
  * mint the canonical user ID and add the routing keys to its user index.
  */
case class UserRegisteredEvent(
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
) derives JsonCodec

case class RegistrationClaimResponse(userId: UserId) derives JsonCodec
