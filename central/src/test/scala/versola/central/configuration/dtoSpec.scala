package versola.central.configuration

import zio.json.*
import zio.test.*

object dtoSpec extends ZIOSpecDefault:

  def spec = suite("DTO JSON Codecs")(
    test("PatchDescription encodes and decodes correctly") {
      val desc = PatchDescription(add = Map("en" -> "Hello"), delete = Set("fr"))
      val json = desc.toJson
      assertTrue(json.fromJson[PatchDescription] == Right(desc))
    },

    test("PatchDescription.patch applies add and delete") {
      val existing = Map("en" -> "Hello", "fr" -> "Bonjour")
      val patch = PatchDescription(add = Map("de" -> "Hallo"), delete = Set("fr"))
      assertTrue(patch.patch(existing) == Map("en" -> "Hello", "de" -> "Hallo"))
    },

    test("ResourceUri.apply creates URI directly") {
      val uri = ResourceUri("https://example.com")
      assertTrue((uri: String) == "https://example.com")
    },

    test("ResourceUri.parse accepts valid absolute URI without path") {
      assertTrue(ResourceUri.parse("https://example.com").isRight)
    },

    test("ResourceUri.parse rejects URI with path") {
      assertTrue(ResourceUri.parse("https://example.com/api").isLeft)
    },

    test("ResourceUri JSON round-trip") {
      val uri = ResourceUri("https://example.com")
      val json = uri.toJson
      assertTrue(json.fromJson[ResourceUri] == Right(uri))
    },
  )
