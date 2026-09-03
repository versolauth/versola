package versola.auth.model

import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

object PasskeyNameSpec extends UnitSpecBase:
  val spec = suite("PasskeyName")(
    test("accepts a non-empty name") {
      assertTrue(PasskeyName.from("My passkey") == Right(PasskeyName("My passkey")))
    },
    test("normalizes surrounding whitespace") {
      assertTrue(PasskeyName.from("  My passkey  ") == Right(PasskeyName("My passkey")))
    },
    test("rejects a blank name") {
      assertTrue(PasskeyName.from("   ") == Left("Passkey name must not be empty"))
    },
    test("JSON decoding validates and normalizes the name") {
      assertTrue(
        "\"  My passkey  \"".fromJson[PasskeyName] == Right(PasskeyName("My passkey")),
        "\"   \"".fromJson[PasskeyName].isLeft,
      )
    },
    test("JSON encoding preserves the underlying name") {
      assertTrue(PasskeyName("My passkey").toJson == "\"My passkey\"")
    },
  )