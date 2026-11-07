package versola.util.revocation

import zio.test.*

/** The key format is the contract between everything that writes the revocation tables and
  * everything that reads it (the replicas of auth and of edge), so it is pinned here by
  * example, not only by round trip: a prefix that changed would round-trip with itself and still
  * break every row already written.
  */
object RevocationKeySpec extends ZIOSpecDefault:

  private val keys = List(
    RevocationKey.Jti("token-1") -> "jti:token-1",
    RevocationKey.Fam("family-1") -> "fam:family-1",
    RevocationKey.Sid("session-1") -> "sid:session-1",
    RevocationKey.Sub("user-1") -> "sub:user-1",
  )

  def spec = suite("RevocationKey")(
    test("encodes each kind under its fixed prefix") {
      assertTrue(keys.forall((key, encoded) => key.encoded == encoded))
    },
    test("decodes what it encodes") {
      assertTrue(keys.forall((key, encoded) => RevocationKey.decode(encoded).contains(key)))
    },
    test("does not decode an empty id, an unknown prefix or no prefix") {
      assertTrue(
        RevocationKey.decode("jti:").isEmpty,
        RevocationKey.decode("sub:").isEmpty,
        RevocationKey.decode("zzz:1").isEmpty,
        RevocationKey.decode("token-1").isEmpty,
      )
    },
    test("keeps a colon inside an id") {
      assertTrue(RevocationKey.decode("sub:a:b").contains(RevocationKey.Sub("a:b")))
    },
  )
