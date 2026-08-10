package versola.e2e.support

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** Generates PKCE `code_verifier` / `code_challenge` pairs (RFC 7636, S256 method). */
object PkceHelper:

  private val random = new SecureRandom()

  /** Returns a pair of (code_verifier, code_challenge). */
  def generate(): (String, String) =
    val verifier  = newVerifier()
    val challenge = s256(verifier)
    (verifier, challenge)

  private def newVerifier(): String =
    val bytes = Array.ofDim[Byte](32)
    random.nextBytes(bytes)
    base64Url(bytes)

  private def s256(verifier: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"))
    base64Url(digest)

  private def base64Url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
