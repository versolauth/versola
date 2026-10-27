package versola.oauth.challenge.password.model

import versola.user.model.UserId

/** The channel the temporary password was to go through has no server behind it: none is configured, or the one saved is
  * switched off or cannot be read. Raised before the password is stored, so nothing is left that nobody can be told. */
final case class PasswordChannelUnavailable(userId: UserId, channel: DeliveryChannel)
  extends RuntimeException(
    s"Cannot deliver temporary password to user $userId via $channel: that channel is not configured or is switched off",
  )
