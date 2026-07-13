package versola.oauth.challenge.password.model

import versola.user.model.UserId

final case class PasswordDeliveryUnavailable(userId: UserId, channel: DeliveryChannel)
    extends RuntimeException(
      s"Cannot deliver temporary password to user $userId via $channel: no matching contact on record",
    )
