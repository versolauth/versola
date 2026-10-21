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

  /** The user's own `name` claim, if they have one. */
  def nameClaim: Option[String] =
    claims.fields.toMap.get("name").collect { case Json.Str(value) if value.trim.nonEmpty => value }

  /** The account name a passkey authenticator shows when the user did not sign in with a credential we know
    * (account settings, a re-authentication): their email, phone or login. Never the user id, which means nothing
    * to a person; a user with none of them has no account name, and the caller falls back to the service's name. */
  def passkeyAccountName: Option[String] =
    email.orElse(phone).orElse(login)

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
