package versola.oauth.identifier

import versola.util.{Email, Phone, UnitSpecBase}
import zio.test.*

object IdentifierChangeCredentialsSpec extends UnitSpecBase:

  private def parse(kind: IdentifierChangeKind, raw: String, prefixes: List[String] = Nil) =
    IdentifierChangeCredentials.parse(kind, raw, prefixes)

  val spec = suite("IdentifierChangeCredentials")(
    test("accepts a phone written in E.164 and nothing but E.164") {
      assertTrue(
        parse(IdentifierChangeKind.Phone, "+77011234567") == Some(Right(Phone("+77011234567"))),
        // Validated, not normalised: the same number written with the national trunk prefix is
        // refused, so the intent and the login_hint can only meet at one spelling.
        parse(IdentifierChangeKind.Phone, "+787011234567").isEmpty,
      )
    },
    test("refuses a phone that is not in international form or not a number") {
      assertTrue(
        parse(IdentifierChangeKind.Phone, "87011234567").isEmpty,
        parse(IdentifierChangeKind.Phone, "+7 701 123 45 67").isEmpty,
        parse(IdentifierChangeKind.Phone, "+1").isEmpty,
        parse(IdentifierChangeKind.Phone, "someone@example.com").isEmpty,
      )
    },
    test("applies the tenant's phone prefixes, when it has any") {
      assertTrue(
        parse(IdentifierChangeKind.Phone, "+77011234567", List("+7")).isDefined,
        parse(IdentifierChangeKind.Phone, "+12025551234", List("+7")).isEmpty,
        parse(IdentifierChangeKind.Phone, "+12025551234", Nil).isDefined,
      )
    },
    test("parses an email, and refuses anything else for it") {
      assertTrue(
        parse(IdentifierChangeKind.Email, "new@example.com") == Some(Left(Email("new@example.com"))),
        parse(IdentifierChangeKind.Email, "+77011234567").isEmpty,
        parse(IdentifierChangeKind.Email, "not an email").isEmpty,
        // Prefixes belong to phones only.
        parse(IdentifierChangeKind.Email, "new@example.com", List("+7")).isDefined,
      )
    },
  )
