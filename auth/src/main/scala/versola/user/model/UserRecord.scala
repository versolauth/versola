package versola.user.model

import versola.util.{Email, Phone}

import java.time.Instant

case class UserRecord(
    id: UserId,
    email: Option[Email],
    phone: Option[Phone]
):
  def createdAt: Instant = id.createdAt

object UserRecord:
  def empty(id: UserId): UserRecord =
    UserRecord(
      id = id,
      email = None,
      phone = None
    )
