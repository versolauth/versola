package versola.loadgen.protocol

import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object PkceSpec extends ZIOSpecDefault:

  private def s256(verifier: String): String =
    Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)))

  def spec = suite("Pkce")(
    test("the challenge is the S256 of the verifier") {
      val pkce = Pkce.generate()
      assertTrue(pkce.challenge == s256(pkce.verifier.value))
    },
    test("the verifier is 32 bytes of unpadded base64url, inside RFC 7636's length bounds") {
      val verifier = Pkce.generate().verifier.value
      assertTrue(
        verifier.length == 43,
        verifier.forall(character => character.isLetterOrDigit || character == '-' || character == '_'),
      )
    },
    test("a fresh pair per call") {
      val pairs = List.fill(64)(Pkce.generate().verifier.value)
      assertTrue(pairs.distinct.size == pairs.size)
    },
    test("the thread-local digest survives repeated use") {
      val verifier = Pkce.generate().verifier.value
      val challenges = List.fill(8)(Entropy.base64Url(Entropy.sha256(verifier.getBytes(StandardCharsets.US_ASCII))))
      assertTrue(challenges.distinct == List(s256(verifier)))
    },
    test("nonces are fixed width and unique") {
      val nonces = List.fill(256)(Entropy.nonce())
      assertTrue(nonces.forall(_.length == 32), nonces.distinct.size == nonces.size)
    },
  )
