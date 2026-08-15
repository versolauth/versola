package versola.oauth.challenge.password.model

import versola.user.model.UserId

final case class PasswordRevealForbidden(userId: UserId)
    extends RuntimeException(
      s"Cannot reveal a temporary password for user $userId: not permitted in production",
    )
