package versola.loadgen.protocol

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom

/** Randomness and hashing for the protocol clients, split by what the value is actually for
  * (versola-loadgen-dev-spec.md §3.3).
  *
  * `SecureRandom` and `MessageDigest` are both stateful and synchronized internally, so the
  * single shared instances `PkceHelper`/`TestAuthenticator` use become a contention point once
  * thousands of fibers share a driver -- they are thread-locals here instead. `state` and the
  * other non-security nonces do not need a CSPRNG at all and use `ThreadLocalRandom`, not
  * `UUID.randomUUID()`, which reads the same shared secure random the PKCE verifier does.
  */
private[protocol] object Entropy:
  private val secureRandoms = ThreadLocal.withInitial(() => SecureRandom())
  private val digests = ThreadLocal.withInitial(() => MessageDigest.getInstance("SHA-256"))
  private val base64 = Base64.getUrlEncoder.withoutPadding()

  def secureRandom: SecureRandom = secureRandoms.get()

  def secureBytes(length: Int): Array[Byte] =
    val bytes = Array.ofDim[Byte](length)
    secureRandoms.get().nextBytes(bytes)
    bytes

  /** Reset before use, not after: a digest left half-updated by a throwing caller would
    * otherwise poison every later hash on that thread.
    */
  def sha256(bytes: Array[Byte]): Array[Byte] =
    val digest = digests.get()
    digest.reset()
    digest.digest(bytes)

  def sha256(value: String): Array[Byte] =
    sha256(value.getBytes(StandardCharsets.UTF_8))

  def base64Url(bytes: Array[Byte]): String =
    base64.encodeToString(bytes)

  /** 128 bits of `state` (or any other value whose only job is to be unique per request).
    * Written zero-padded into a fixed 32-char buffer rather than via `Long.toHexString`, which
    * drops leading zeros and would make two different pairs of longs share a rendering.
    */
  def nonce(): String =
    val random = ThreadLocalRandom.current()
    val chars = Array.ofDim[Char](32)
    writeHex(random.nextLong(), chars, 0)
    writeHex(random.nextLong(), chars, 16)
    String(chars)

  private val hex = "0123456789abcdef".toCharArray

  private def writeHex(value: Long, into: Array[Char], offset: Int): Unit =
    var index = 0
    while index < 16 do
      into(offset + index) = hex(((value >>> ((15 - index) * 4)) & 0xfL).toInt)
      index += 1
