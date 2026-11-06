package versola.loadgen.antifraud

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The client's half of the proof-of-work challenge `auth` puts to a request it does not trust (`PowChallengeVerifier`
  * in `auth`; `central-ui/forms/shared/pow.ts` is the browser's half): find a decimal `nonce` for which SHA-256 of the
  * UTF-8 text `<seed>:<nonce>` starts with `difficulty` zero bits.
  *
  * The rule is the server's and is restated here, not shared: this process drives `auth` over HTTP and links none of
  * it. `PowSpec` holds the vectors of `pow.test.ts` (the same seed, the same nonces) so that the three agree.
  */
object Pow:

  /** The `kind` of the challenge this solves (`PowChallengeVerifier.Kind`). */
  val Kind = "pow"

  /** The longest nonce the server accepts: a decimal number of up to this many characters. */
  val NonceLength = 20

  /** A nonce that was found, and the hashes it took: the nonces are tried in order from 0, so a nonce `n` is the
    * `n + 1`-th hash. */
  case class Solution(nonce: String, hashes: Long)

  /** The zero bits a digest starts with. */
  def leadingZeroBits(digest: Array[Byte]): Int =
    var bits = 0
    var i = 0
    while i < digest.length do
      val b = digest(i) & 0xff
      if b == 0 then
        bits += 8
        i += 1
      else
        bits += Integer.numberOfLeadingZeros(b) - 24
        i = digest.length
    bits

  /** The server's check of one candidate: a non-empty decimal string of at most [[NonceLength]] characters whose hash
    * with the seed starts with `difficulty` zero bits. */
  def isSolution(seed: String, nonce: String, difficulty: Int): Boolean =
    nonce.nonEmpty && nonce.length <= NonceLength && nonce.forall(c => c >= '0' && c <= '9') && {
      val digest = MessageDigest.getInstance("SHA-256").digest(s"$seed:$nonce".getBytes(StandardCharsets.UTF_8))
      leadingZeroBits(digest) >= difficulty
    }

  /** Finds the first nonce, from 0 up, that solves `seed` at `difficulty`. `None` when `cancelled` says so (read every
    * 4096 hashes), or when no nonce of [[NonceLength]] digits is left, which is no case that can be met in a life. A
    * difficulty of 0 or less is solved by the first nonce.
    *
    * One digest object and one buffer for the whole search: the message is built in place and the digest is written
    * into a fixed array, so the loop allocates nothing. */
  def solve(seed: String, difficulty: Int, cancelled: () => Boolean = () => false): Option[Solution] =
    val digest = MessageDigest.getInstance("SHA-256")
    val prefix = s"$seed:".getBytes(StandardCharsets.UTF_8)
    val message = new Array[Byte](prefix.length + NonceLength)
    System.arraycopy(prefix, 0, message, 0, prefix.length)
    val out = new Array[Byte](32)
    var nonce = 0L
    var found = false
    var stopped = false
    while !found && !stopped do
      val length = writeDecimal(message, prefix.length, nonce)
      digest.update(message, 0, prefix.length + length)
      digest.digest(out, 0, 32)
      if leadingZeroBits(out) >= difficulty then found = true
      else
        nonce += 1
        if (nonce & 0xfffL) == 0L && cancelled() then stopped = true
    if found then Some(Solution(nonce = nonce.toString, hashes = nonce + 1)) else None

  /** Writes `value` in decimal at `from` and returns how many bytes that took. */
  private def writeDecimal(into: Array[Byte], from: Int, value: Long): Int =
    var length = 1
    var rest = value / 10
    while rest > 0 do
      length += 1
      rest /= 10
    var position = from + length - 1
    var remaining = value
    while position >= from do
      into(position) = ('0' + (remaining % 10).toInt).toByte
      remaining /= 10
      position -= 1
    length
