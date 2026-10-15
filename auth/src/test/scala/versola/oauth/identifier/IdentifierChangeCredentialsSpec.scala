package versola.oauth.identifier

import versola.oauth.client.model.PhoneRegion
import versola.util.{Email, Phone, UnitSpecBase}
import zio.test.*

object IdentifierChangeCredentialsSpec extends UnitSpecBase:

  private val kz = PhoneRegion("KZ", "+7", 10, "7\\d{9}", false)

  private def parse(kind: IdentifierChangeKind, raw: String, regions: List[PhoneRegion] = Nil) =
    IdentifierChangeCredentials.parse(kind, raw, regions)

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
    test("applies the tenant's phone regions, when it has any") {
      assertTrue(
        parse(IdentifierChangeKind.Phone, "+77011234567", List(kz)).isDefined,
        parse(IdentifierChangeKind.Phone, "+12025551234", List(kz)).isEmpty,
        parse(IdentifierChangeKind.Phone, "+12025551234", Nil).isDefined,
      )
    },
    test("parses an email, and refuses anything else for it") {
      assertTrue(
        parse(IdentifierChangeKind.Email, "new@example.com") == Some(Left(Email("new@example.com"))),
        parse(IdentifierChangeKind.Email, "+77011234567").isEmpty,
        parse(IdentifierChangeKind.Email, "not an email").isEmpty,
        // Regions belong to phones only.
        parse(IdentifierChangeKind.Email, "new@example.com", List(kz)).isDefined,
      )
    },
  )
