package versola.email.model

import versola.util.UnitSpecBase
import zio.test.*

object EmailAddressSpec extends UnitSpecBase:

  val spec = suite("EmailAddress")(
    suite("from") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, EmailAddress],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = EmailAddress.from(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept simple email",
          input = "user@example.com",
          expectedResult = Right(EmailAddress("user@example.com"))
        ),
        TestCase(
          description = "accept email with subdomain",
          input = "user@mail.example.com",
          expectedResult = Right(EmailAddress("user@mail.example.com"))
        ),
        TestCase(
          description = "accept email with plus sign",
          input = "user+tag@example.com",
          expectedResult = Right(EmailAddress("user+tag@example.com"))
        ),
        TestCase(
          description = "accept email with dots",
          input = "first.last@example.com",
          expectedResult = Right(EmailAddress("first.last@example.com"))
        ),
        TestCase(
          description = "accept email with numbers",
          input = "user123@example123.com",
          expectedResult = Right(EmailAddress("user123@example123.com"))
        ),
        TestCase(
          description = "accept email with underscores",
          input = "user_name@example.com",
          expectedResult = Right(EmailAddress("user_name@example.com"))
        ),
        TestCase(
          description = "accept email with hyphens in domain",
          input = "user@my-domain.com",
          expectedResult = Right(EmailAddress("user@my-domain.com"))
        ),

        // Invalid cases
        TestCase(
          description = "reject email without @",
          input = "userexample.com",
          expectedResult = Left("userexample.com is invalid email address")
        ),
        TestCase(
          description = "reject email without domain",
          input = "user@",
          expectedResult = Left("user@ is invalid email address")
        ),
        TestCase(
          description = "reject email without user",
          input = "@example.com",
          expectedResult = Left("@example.com is invalid email address")
        ),
        TestCase(
          description = "reject email without TLD",
          input = "user@example",
          expectedResult = Left("user@example is invalid email address")
        ),
        TestCase(
          description = "reject empty string",
          input = "",
          expectedResult = Left(" is invalid email address")
        ),
        TestCase(
          description = "reject email with spaces",
          input = "user @example.com",
          expectedResult = Left("user @example.com is invalid email address")
        ),
        TestCase(
          description = "reject email with multiple @",
          input = "user@@example.com",
          expectedResult = Left("user@@example.com is invalid email address")
        ),
      ).map(testCase)
    }
  )
