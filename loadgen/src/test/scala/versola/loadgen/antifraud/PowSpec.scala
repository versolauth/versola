package versola.loadgen.antifraud

import zio.test.*

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The vectors of `central-ui/forms/shared/pow.test.ts`, run against the Scala solver: the same seed, the same nonces,
  * checked against a count of zero bits made independently of [[Pow]], so that the browser's kernel, the server's check
  * (`PowChallengeVerifier`) and this one agree on the rule. */
object PowSpec extends ZIOSpecDefault:

  /** Zero bits of SHA-256 of `seed:nonce`, counted from a BigInteger rather than by [[Pow.leadingZeroBits]]. */
  private def zeros(seed: String, nonce: BigInt): Int =
    val digest = MessageDigest.getInstance("SHA-256").digest(s"$seed:$nonce".getBytes(StandardCharsets.UTF_8))
    256 - BigInteger(1, digest).bitLength

  def spec = suite("Pow")(
    test("agrees with the platform on every nonce it accepts, and on the ones it passes by") {
      val seed = "q3Zr_8aK1x0Wm2c4VbN7eA"
      val disagreements = (0 until 3000).filter(nonce => Pow.isSolution(seed, nonce.toString, 8) != (zeros(seed, BigInt(nonce)) >= 8))
      assertTrue(disagreements.isEmpty)
    },
    test("handles nonces of every length, up to the ones the server accepts") {
      val seed = "q3Zr_8aK1x0Wm2c4VbN7eA"
      val lengths = List("0", "9", "10", "99", "100", "123456789", "4294967296", "9007199254740991", "99999999999999999999")
      assertTrue(
        lengths.forall(Pow.isSolution(seed, _, 0)),
        // one character over what the server takes is not a solution, whatever its hash
        !Pow.isSolution(seed, "100000000000000000000", 0),
        !Pow.isSolution(seed, "", 0),
        !Pow.isSolution(seed, "12a", 0),
        !Pow.isSolution(seed, "-1", 0),
      )
    },
    test("leadingZeroBits counts across zero bytes and within the first non-zero one") {
      assertTrue(
        Pow.leadingZeroBits(Array[Byte](0, 0, 0x1f.toByte)) == 19,
        Pow.leadingZeroBits(Array[Byte](0x80.toByte)) == 0,
        Pow.leadingZeroBits(Array[Byte](0, 1)) == 15,
        Pow.leadingZeroBits(Array.fill[Byte](4)(0)) == 32,
      )
    },
    test("solve finds a nonce that the server-side rule accepts, and says how many hashes it took") {
      val seed = "seed-1"
      val found = Pow.solve(seed, 12).get
      assertTrue(
        found.nonce.forall(_.isDigit),
        Pow.isSolution(seed, found.nonce, 12),
        zeros(seed, BigInt(found.nonce)) >= 12,
        found.hashes == found.nonce.toLong + 1,
        // the first one: no smaller nonce solves it
        (0L until found.nonce.toLong).forall(n => zeros(seed, BigInt(n)) < 12),
      )
    },
    test("solve of difficulty 0 is the first nonce, and a longer search crosses the digit boundaries it writes") {
      val seed = "seed-3"
      val found = Pow.solve(seed, 16).get
      assertTrue(
        Pow.solve(seed, 0).contains(Pow.Solution("0", 1)),
        found.nonce.length >= 1 && Pow.isSolution(seed, found.nonce, 16),
      )
    },
    test("solve stops when it is cancelled") {
      assertTrue(Pow.solve("seed-2", 60, () => true).isEmpty)
    },
  )
