package versola.user.model

import versola.util.UnitSpecBase
import zio.test.*

object FirstNameSpec extends UnitSpecBase:

  val spec = suite("FirstName")(
    suite("from") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, FirstName],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = FirstName.from(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept Latin name",
          input = "John",
          expectedResult = Right(FirstName("John"))
        ),

        TestCase(
          description = "accept Cyrillic name",
          input = "Иван",
          expectedResult = Right(FirstName("Иван"))
        ),

        TestCase(
          description = "accept name with hyphen",
          input = "Mary-Jane",
          expectedResult = Right(FirstName("Mary-Jane"))
        ),

        TestCase(
          description = "accept name with space",
          input = "Jean Paul",
          expectedResult = Right(FirstName("Jean Paul"))
        ),

        TestCase(
          description = "accept minimum length (2 chars)",
          input = "Jo",
          expectedResult = Right(FirstName("Jo"))
        ),

        TestCase(
          description = "accept maximum length (30 chars)",
          input = "Abcdefghijklmnopqrstuvwxyzabcd",
          expectedResult = Right(FirstName("Abcdefghijklmnopqrstuvwxyzabcd"))
        ),

        TestCase(
          description = "accept mixed Latin and Cyrillic",
          input = "John-Иван",
          expectedResult = Right(FirstName("John-Иван"))
        ),

        // Invalid cases
        TestCase(
          description = "reject single character",
          input = "J",
          expectedResult = Left("J is invalid name part")
        ),

        TestCase(
          description = "reject empty string",
          input = "",
          expectedResult = Left(" is invalid name part")
        ),

        TestCase(
          description = "reject too long name",
          input = "Abcdefghijklmnopqrstuvwxyzabcde",
          expectedResult = Left("Abcdefghijklmnopqrstuvwxyzabcde is invalid name part")
        ),

        TestCase(
          description = "reject numbers",
          input = "John123",
          expectedResult = Left("John123 is invalid name part")
        ),

        TestCase(
          description = "reject special characters",
          input = "John@",
          expectedResult = Left("John@ is invalid name part")
        ),

        TestCase(
          description = "reject other scripts",
          input = "李明",
          expectedResult = Left("李明 is invalid name part")
        ),
      ).map(testCase)
    }
  )
