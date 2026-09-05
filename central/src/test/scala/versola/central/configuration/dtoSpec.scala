package versola.central.configuration

import versola.central.configuration.clients.{AuthFlow, ClientId, RegistrationFlow}
import versola.util.Patch
import zio.json.*
import zio.test.*

object dtoSpec extends ZIOSpecDefault:

  /** Decodes an UpdateClientRequest carrying only the required fields plus the given raw field JSON. */
  private def decodeUpdate(field: String): Either[String, UpdateClientRequest] =
    val required =
      """"clientId":"web-app",""" +
        """"redirectUris":{"add":[],"remove":[]},""" +
        """"scope":{"add":[],"remove":[]},""" +
        """"permissions":{"add":[],"remove":[]}"""
    s"""{$required$field}""".fromJson[UpdateClientRequest]

  def spec = suite("DTO JSON Codecs")(
    suite("UpdateClientRequest nullable fields distinguish absent from null")(
      test("an absent auth flow means no change, an explicit null clears it") {
        val absent = decodeUpdate("")
        val cleared = decodeUpdate(""","authFlow":null""")
        val set = decodeUpdate(s""","authFlow":${AuthFlow.default.toJson}""")
        assertTrue(
          absent.map(_.authFlow) == Right(None),
          cleared.map(_.authFlow) == Right(Some(Patch.Deleted)),
          set.map(_.authFlow) == Right(Some(Patch.Modified(AuthFlow.default))),
        )
      },
      test("an absent registration flow means no change, an explicit null clears it") {
        val absent = decodeUpdate("")
        val cleared = decodeUpdate(""","registrationFlow":null""")
        val set = decodeUpdate(s""","registrationFlow":${RegistrationFlow.default.toJson}""")
        assertTrue(
          absent.map(_.registrationFlow) == Right(None),
          cleared.map(_.registrationFlow) == Right(Some(Patch.Deleted)),
          set.map(_.registrationFlow) == Right(Some(Patch.Modified(RegistrationFlow.default))),
        )
      },
      test("a finite consent duration is decoded from seconds") {
        val decoded = decodeUpdate(
          ""","consentFlow":{"allowPartial":true,"rememberDuration":1209600}""",
        )
        assertTrue(
          decoded.map(_.consentFlow) == Right(
            Some(Patch.Modified(ConsentFlowDto(allowPartial = true, rememberDuration = Some(1209600L))))
          )
        )
      },
      test("an absent logout URI means no change, an explicit null clears it") {
        val absent = decodeUpdate("")
        val cleared = decodeUpdate(""","frontChannelLogoutUri":null""")
        val set = decodeUpdate(""","frontChannelLogoutUri":"https://example.com/logout"""")
        assertTrue(
          absent.map(_.frontChannelLogoutUri) == Right(None),
          cleared.map(_.frontChannelLogoutUri) == Right(Some(Patch.Deleted)),
          set.map(_.frontChannelLogoutUri) == Right(Some(Patch.Modified("https://example.com/logout"))),
        )
      },
    ),
    test("ConsentFlowDto encodes a finite duration as seconds") {
      val json = ConsentFlowDto(allowPartial = false, rememberDuration = Some(1209600L)).toJson
      assertTrue(
        json.contains("\"rememberDuration\":1209600"),
        !json.contains("PT"),
      )
    },
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
