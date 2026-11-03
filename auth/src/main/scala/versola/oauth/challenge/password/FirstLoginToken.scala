package versola.oauth.challenge.password

import versola.util.{Base64Url, SecureRandom}
import zio.UIO

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The one-time token of the bootstrap admin's first-login link (issue #824). The token is 256 random
  * bits, so a plain SHA-256 is enough at rest: there is nothing to brute-force, and a salt or a slow
  * hash would only stop the lookup by hash that `first_login_tokens` is keyed on.
  */
object FirstLoginToken:
  /** The query parameter that carries the token on the link and through edge's `/login/{presetId}` to `/authorize`. */
  val ParamName = "first_login_token"

  def generate(secureRandom: SecureRandom): UIO[String] =
    secureRandom.nextBytes(32).map(bytes => Base64Url.encode(bytes).toString)

  def hash(token: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
