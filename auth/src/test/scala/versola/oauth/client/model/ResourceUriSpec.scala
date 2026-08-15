package versola.oauth.client.model

import versola.util.UnitSpecBase
import zio.test.*

object ResourceUriSpec extends UnitSpecBase:

  def spec = suite("ResourceUri")(
    suite("splitFormValue")(
      test("returns a single-element list for one resource") {
        assertTrue(ResourceUri.splitFormValue("https://api.example.com") == List("https://api.example.com"))
      },
      test("splits a zio-http comma-joined repeated field into its resources") {
        assertTrue(
          ResourceUri.splitFormValue("https://api.example.com,resource://internal-api") ==
            List("https://api.example.com", "resource://internal-api"),
        )
      },
      test("splits more than two comma-joined resources") {
        assertTrue(
          ResourceUri.splitFormValue("https://api.example.com,resource://internal-api,https://reports.example.com") ==
            List("https://api.example.com", "resource://internal-api", "https://reports.example.com"),
        )
      },
      test("does not split a comma that is not followed by a URI scheme") {
        assertTrue(
          ResourceUri.splitFormValue("https://api.example.com,not-a-scheme") ==
            List("https://api.example.com,not-a-scheme"),
        )
      },
    ),
  )
