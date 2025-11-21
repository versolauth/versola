package versola.auth.model

import versola.util.UnitSpecBase
import zio.schema.Schema
import zio.test.*

object OtpCodeSpec extends UnitSpecBase:

  val spec = suite("OtpCode")(
    suite("schema validation") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, OtpCode],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val schema = summon[Schema[OtpCode]]
        val result = schema.fromDynamic(zio.schema.DynamicValue.Primitive(tc.input, zio.schema.StandardType.StringType))
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept exactly 6 digits",
          input = "123456",
          expectedResult = Right(OtpCode("123456"))
        ),

        TestCase(
          description = "accept 6 digits starting with zero",
          input = "012345",
          expectedResult = Right(OtpCode("012345"))
        ),

        // Invalid cases
        TestCase(
          description = "reject empty string",
          input = "",
          expectedResult = Left("Invalid OTP code")
        ),

        TestCase(
          description = "reject string shorter than 6 characters",
          input = "12345",
          expectedResult = Left("Invalid OTP code")
        ),

        TestCase(
          description = "reject string longer than 6 characters",
          input = "1234567",
          expectedResult = Left("Invalid OTP code")
        ),

        TestCase(
          description = "reject non-digit characters",
          input = "12345a",
          expectedResult = Left("Invalid OTP code")
        ),
      ).map(testCase)
    }
  )
