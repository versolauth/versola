package versola.user

import versola.util.{Email, Phone, UnitSpecBase}
import zio.json.*
import zio.test.*

object UserIntentSpec extends UnitSpecBase:

  val spec = suite("UserIntent")(
    test("is stored as one JSON object whose type names the kind, so new kinds are new cases and not new columns") {
      assertTrue(
        (UserIntent.ChangePhone(Phone("+77011234567")): UserIntent).toJson == """{"type":"change_phone","phone":"+77011234567"}""",
        (UserIntent.ChangeEmail(Email("new@example.com")): UserIntent).toJson == """{"type":"change_email","email":"new@example.com"}""",
      )
    },
    test("round-trips every kind") {
      val intents: List[UserIntent] = List(UserIntent.ChangePhone(Phone("+77011234567")), UserIntent.ChangeEmail(Email("new@example.com")))
      assertTrue(intents.map(_.toJson.fromJson[UserIntent]) == intents.map(Right(_)))
    },
    test("refuses a type it does not know") {
      assertTrue("""{"type":"delete_account"}""".fromJson[UserIntent].isLeft)
    },
    test("is built from the new credential of a change") {
      assertTrue(
        UserIntent.change(Right(Phone("+77011234567"))) == UserIntent.ChangePhone(Phone("+77011234567")),
        UserIntent.change(Left(Email("new@example.com"))) == UserIntent.ChangeEmail(Email("new@example.com")),
      )
    },
  )
