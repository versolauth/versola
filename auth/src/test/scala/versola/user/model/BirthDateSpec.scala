package versola.user.model

import versola.util.UnitSpecBase
import zio.test.*

import java.time.LocalDate

object BirthDateSpec extends UnitSpecBase:

  val spec = suite("BirthDate")(
    suite("from") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, BirthDate],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = BirthDate.from(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept valid ISO date",
          input = "1990-01-15",
          expectedResult = Right(BirthDate(LocalDate.of(1990, 1, 15)))
        ),

        TestCase(
          description = "accept leap year date",
          input = "2000-02-29",
          expectedResult = Right(BirthDate(LocalDate.of(2000, 2, 29)))
        ),

        // Invalid format cases
        TestCase(
          description = "reject empty string",
          input = "",
          expectedResult = Left(" is invalid birth date")
        ),

        TestCase(
          description = "reject non-date string",
          input = "not-a-date",
          expectedResult = Left("not-a-date is invalid birth date")
        ),

        TestCase(
          description = "reject wrong format",
          input = "15/01/1990",
          expectedResult = Left("15/01/1990 is invalid birth date")
        ),

        // Invalid date values
        TestCase(
          description = "reject invalid month",
          input = "1990-13-15",
          expectedResult = Left("1990-13-15 is invalid birth date")
        ),

        TestCase(
          description = "reject invalid day",
          input = "1990-02-30",
          expectedResult = Left("1990-02-30 is invalid birth date")
        ),

        TestCase(
          description = "reject February 29 in non-leap year",
          input = "1999-02-29",
          expectedResult = Left("1999-02-29 is invalid birth date")
        ),

        // Edge cases
        TestCase(
          description = "reject incomplete date",
          input = "1990-01",
          expectedResult = Left("1990-01 is invalid birth date")
        ),
      ).map(testCase)
    }
  )

