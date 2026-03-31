package versola.central.configuration

import zio.test.*

object ResourceUriSpec extends ZIOSpecDefault:

  def spec = suite("ResourceUri")(
    suite("parse") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, ResourceUri],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = ResourceUri.parse(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        TestCase(
          description = "accept https URI",
          input = "https://api.example.com",
          expectedResult = Right(ResourceUri("https://api.example.com")),
        ),
        TestCase(
          description = "reject URN resource unsupported by zio-http URL",
          input = "urn:versola:permission:admin:view",
          expectedResult = Left("Invalid URI format: urn:versola:permission:admin:view"),
        ),
        TestCase(
          description = "reject URI with path",
          input = "https://api.example.com/users",
          expectedResult = Left("Resource URI path must be empty"),
        ),
        TestCase(
          description = "reject URI with slash path",
          input = "https://api.example.com/",
          expectedResult = Left("Resource URI path must be empty"),
        ),
        TestCase(
          description = "reject URI with query",
          input = "https://api.example.com?env=prod",
          expectedResult = Left("Resource URI query must be empty"),
        ),
        TestCase(
          description = "reject URI with fragment",
          input = "https://api.example.com#users",
          expectedResult = Left("Resource URI fragment must be empty"),
        ),
        TestCase(
          description = "reject relative URI",
          input = "/api/users",
          expectedResult = Left("Resource URI must be absolute"),
        ),
        TestCase(
          description = "reject malformed URI",
          input = "https://[bad",
          expectedResult = Left("Invalid URI format: https://[bad"),
        ),
      ).map(testCase)
    },
  )
