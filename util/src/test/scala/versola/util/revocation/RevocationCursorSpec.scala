package versola.util.revocation

import zio.*
import zio.test.*

import java.time.Instant

/** The cursor is what the feed hands out and an edge stores between polls and reads back to start a poll behind
  * where the last one ended, so what it writes is pinned by example as well as by round trip.
  */
object RevocationCursorSpec extends ZIOSpecDefault:

  private val cursor = RevocationCursor(Instant.parse("2026-11-06T10:00:00.123456Z"), "sub:user-1")

  def spec = suite("RevocationCursor")(
    test("the beginning is the empty string, both ways") {
      assertTrue(
        RevocationCursor.Beginning.encoded == "",
        RevocationCursor.decode("").contains(RevocationCursor.Beginning),
      )
    },
    test("decodes what it encodes, to the microsecond and whatever the key holds") {
      val odd = RevocationCursor(cursor.revokedAt, "jti:a|b=c/d")
      assertTrue(
        RevocationCursor.decode(cursor.encoded).contains(cursor),
        RevocationCursor.decode(odd.encoded).contains(odd),
      )
    },
    test("does not decode what it did not write") {
      assertTrue(
        RevocationCursor.decode("not-a-cursor").isEmpty,
        RevocationCursor.decode("!!!").isEmpty,
        // valid base64url of text with no separator, and of a separator with no instant before it
        RevocationCursor.decode(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString("no separator".getBytes)).isEmpty,
        RevocationCursor.decode(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString("|key".getBytes)).isEmpty,
      )
    },
    test("rewound goes back by the overlap and drops the key, so the whole second is read again") {
      val back = cursor.rewound(30.seconds)
      assertTrue(back.revokedAt == cursor.revokedAt.minusSeconds(30), back.revokedKey == "")
    },
    test("rewound leaves the beginning where it is") {
      assertTrue(RevocationCursor.Beginning.rewound(30.seconds) == RevocationCursor.Beginning)
    },
  )
