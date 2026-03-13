package versola.user.model

import versola.util.{Email, Phone}
import zio.json.ast.Json

import java.time.Instant

case class UserRecord(
    id: UserId,
    email: Option[Email],
    phone: Option[Phone],
    claims: Json,
):
  def createdAt: Instant = id.createdAt

object UserRecord:
  def empty(id: UserId): UserRecord =
    UserRecord(
      id = id,
      email = None,
      phone = None,
      claims = Json.Obj(),
    )
