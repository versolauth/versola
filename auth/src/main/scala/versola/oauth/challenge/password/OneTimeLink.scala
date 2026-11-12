package versola.oauth.challenge.password

import versola.user.model.UserId
import versola.util.{Base64Url, SecureRandom}
import zio.UIO

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A one-time link: a random token that someone is sent (the bootstrap admin through the log line, later a user
  * through an email) and that is spent by the first request carrying it. What opening it does is decided by its
  * [[OneTimeLinkPurpose]], stored with the row, so a new kind of link is a new purpose and a handler, not a new table.
  *
  * The token is 256 random bits, so a plain SHA-256 is enough at rest: there is nothing to brute-force, and a salt or
  * a slow hash would only stop the lookup by hash that `one_time_links` is keyed on.
  */
object OneTimeLink:
  /** The query parameter that carries the token on the link and through edge's `/login/{presetId}` to `/authorize`. */
  val ParamName = "link_token"

  def generate(secureRandom: SecureRandom): UIO[String] =
    secureRandom.nextBytes(32).map(bytes => Base64Url.encode(bytes).toString)

  def hash(token: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))

/** What a link is for. The stored value is part of the schema: never rename one, add a new one. */
enum OneTimeLinkPurpose(val value: String):
  /** The bootstrap admin's first login (issue #824): the password step counts as passed and a permanent password is owed. */
  case FirstLogin extends OneTimeLinkPurpose("first_login")

object OneTimeLinkPurpose:
  def fromValue(value: String): Option[OneTimeLinkPurpose] = values.find(_.value == value)

/** A link that has just been spent: whose it was and what it was for. */
case class ConsumedLink(userId: UserId, purpose: OneTimeLinkPurpose)
