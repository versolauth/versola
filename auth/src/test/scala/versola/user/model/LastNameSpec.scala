package versola.user.model

import versola.util.UnitSpecBase
import zio.test.*

object LastNameSpec extends UnitSpecBase:

  val spec = suite("LastName")(
    suite("from") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, LastName],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = LastName.from(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept Latin surname",
          input = "Smith",
          expectedResult = Right(LastName("Smith"))
        ),

        TestCase(
          description = "accept Cyrillic surname",
          input = "Иванов",
          expectedResult = Right(LastName("Иванов"))
        ),

        TestCase(
          description = "accept hyphenated surname",
          input = "Smith-Jones",
          expectedResult = Right(LastName("Smith-Jones"))
        ),

        TestCase(
          description = "accept compound surname",
          input = "Van Der Berg",
          expectedResult = Right(LastName("Van Der Berg"))
        ),

        TestCase(
          description = "accept minimum length",
          input = "Li",
          expectedResult = Right(LastName("Li"))
        ),

        TestCase(
          description = "accept maximum length",
          input = "Abcdefghijklmnopqrstuvwxyzabcd",
          expectedResult = Right(LastName("Abcdefghijklmnopqrstuvwxyzabcd"))
        ),

        TestCase(
          description = "accept Scottish surname",
          input = "McDonald",
          expectedResult = Right(LastName("McDonald"))
        ),

        // Invalid cases
        TestCase(
          description = "reject single character",
          input = "S",
          expectedResult = Left("S is invalid name part")
        ),

        TestCase(
          description = "reject empty string",
          input = "",
          expectedResult = Left(" is invalid name part")
        ),

        TestCase(
          description = "reject too long surname",
          input = "Abcdefghijklmnopqrstuvwxyzabcde",
          expectedResult = Left("Abcdefghijklmnopqrstuvwxyzabcde is invalid name part")
        ),

        TestCase(
          description = "reject numbers",
          input = "Smith123",
          expectedResult = Left("Smith123 is invalid name part")
        ),

        TestCase(
          description = "reject special characters",
          input = "Smith@",
          expectedResult = Left("Smith@ is invalid name part")
        ),

        TestCase(
          description = "reject other scripts",
          input = "李",
          expectedResult = Left("李 is invalid name part")
        ),
      ).map(testCase)
    }
  )
