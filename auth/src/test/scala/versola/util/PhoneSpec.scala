package versola.util

import zio.test.*

object PhoneSpec extends UnitSpecBase:

  val spec = suite("Phone")(
    suite("parse") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, Phone],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = Phone.parse(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept phone with country code",
          input = "+12025551234",
          expectedResult = Right(Phone("+12025551234"))
        ),

        TestCase(
          description = "accept Russian phone number",
          input = "+79152234455",
          expectedResult = Right(Phone("+79152234455"))
        ),

        TestCase(
          description = "not accept phone without plus sign",
          input = "12025551234",
          expectedResult = Left("Missing or invalid default region.")
        ),

        TestCase(
          description = "accept minimum length (9 digits)",
          input = "+123456789",
          expectedResult = Left("+123456789 is invalid phone number")
        ),

        TestCase(
          description = "reject invalid country code (123)",
          input = "+123456789012345",
          expectedResult = Left("+123456789012345 is invalid phone number")
        ),

        TestCase(
          description = "accept valid German phone number (14 digits)",
          input = "+4915123456789",
          expectedResult = Right(Phone("+4915123456789"))
        ),

        // Invalid cases
        TestCase(
          description = "reject empty string",
          input = "",
          expectedResult = Left(" is invalid phone number")
        ),

        TestCase(
          description = "reject phone with letters",
          input = "+1202555ABCD",
          expectedResult = Left("+1202555ABCD is invalid phone number")
        ),

        TestCase(
          description = "reject phone with formatting",
          input = "+1 202 555 1234",
          expectedResult = Left("+1 202 555 1234 is invalid phone number")
        ),

        TestCase(
          description = "reject too short phone",
          input = "+12345678",
          expectedResult = Left("+12345678 is invalid phone number")
        ),

        TestCase(
          description = "reject too long phone",
          input = "+1234567890123456",
          expectedResult = Left("+1234567890123456 is invalid phone number")
        ),

        TestCase(
          description = "reject invalid country code",
          input = "+0123456789",
          expectedResult = Left("Could not interpret numbers after plus-sign.")
        ),
      ).map(testCase)
    }
  )
