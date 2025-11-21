package versola.user.model

import versola.util.UnitSpecBase
import zio.test.*

object MiddleNameSpec extends UnitSpecBase:

  val spec = suite("MiddleName")(
    suite("from") {
      case class TestCase(
          description: String,
          input: String,
          expectedResult: Either[String, MiddleName],
      )

      def testCase(tc: TestCase) = test(tc.description) {
        val result = MiddleName.from(tc.input)
        assertTrue(result == tc.expectedResult)
      }

      List(
        // Valid cases
        TestCase(
          description = "accept Latin middle name",
          input = "Michael",
          expectedResult = Right(MiddleName("Michael"))
        ),

        TestCase(
          description = "accept Cyrillic patronymic",
          input = "Иванович",
          expectedResult = Right(MiddleName("Иванович"))
        ),

        TestCase(
          description = "accept name with hyphen",
          input = "Jean-Pierre",
          expectedResult = Right(MiddleName("Jean-Pierre"))
        ),

        TestCase(
          description = "accept compound name",
          input = "Van Der Berg",
          expectedResult = Right(MiddleName("Van Der Berg"))
        ),

        TestCase(
          description = "accept minimum length",
          input = "Al",
          expectedResult = Right(MiddleName("Al"))
        ),

        TestCase(
          description = "accept maximum length",
          input = "Abcdefghijklmnopqrstuvwxyzabcd",
          expectedResult = Right(MiddleName("Abcdefghijklmnopqrstuvwxyzabcd"))
        ),

        // Invalid cases
        TestCase(
          description = "reject single character",
          input = "M",
          expectedResult = Left("M is invalid name part")
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
          input = "Michael123",
          expectedResult = Left("Michael123 is invalid name part")
        ),

        TestCase(
          description = "reject special characters",
          input = "Michael@",
          expectedResult = Left("Michael@ is invalid name part")
        ),

        TestCase(
          description = "reject other scripts",
          input = "李明",
          expectedResult = Left("李明 is invalid name part")
        ),
      ).map(testCase)
    }
  )
