package versola.user.model

import versola.util.{Email, Phone}
import zio.json.ast.Json

import java.time.Instant

case class UserRecord(
    id: UserId,
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
    claims: Json.Obj,
    uiLocales: Option[List[String]],
):
  def createdAt: Instant = id.createdAt

  /** The name a passkey authenticator shows in its account picker: the "name" claim if
    * present, falling back to email, phone, login, and finally the user id. */
  def passkeyDisplayName: String =
    claims.fields.toMap.get("name")
      .collect { case Json.Str(value) => value }
      .orElse(email)
      .orElse(phone)
      .orElse(login)
      .getOrElse(id.toString)

object UserRecord:
  def empty(id: UserId): UserRecord =
    UserRecord(
      id = id,
      email = None,
      phone = None,
      login = None,
      claims = Json.Obj(),
      uiLocales = None,
    )
