package versola.edge.login

import versola.util.Base64

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** What ties a pending login to the browser that started it (RFC 9700 §4.7, RFC 6749 §10.12): a random
  * secret that `/login` hands to that browser in a cookie and `/complete` must get back. Only the SHA-256
  * of it is stored, so a read of `pending_logins` does not yield a value the cookie would accept.
  */
object BrowserBinding:

  def hash(secret: String): String =
    Base64.urlEncode(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)))

  /** Whether `presented`, the cookie value the browser sent, belongs to a login stored with `storedHash`.
    * A login without a hash was started before the binding existed, by an edge that set no cookie, and is
    * accepted for the rest of its `loginTtl`; new logins always carry one. */
  def accepts(storedHash: Option[String], presented: Option[String]): Boolean =
    storedHash.forall: stored =>
      presented.exists: secret =>
        MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8))
