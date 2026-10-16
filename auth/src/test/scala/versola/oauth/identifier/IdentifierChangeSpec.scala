package versola.oauth.identifier

import versola.oauth.client.model.Acr
import versola.util.{Email, Phone, UnitSpecBase}
import zio.json.*
import zio.test.*

object IdentifierChangeSpec extends UnitSpecBase:

  private val phoneChange = IdentifierChange(IdentifierChangeKind.Phone, Right(Phone("+77011234567")), IdentifierChangeStage.Pending)
  private val emailChange = IdentifierChange(IdentifierChangeKind.Email, Left(Email("new@example.com")), IdentifierChangeStage.VerifyNew)

  val spec = suite("IdentifierChange")(
    suite("kind")(
      test("is named by exactly two reserved ACR values") {
        assertTrue(
          IdentifierChangeKind.fromAcr(Acr("urn:versola:acr:phone-change")) == Some(IdentifierChangeKind.Phone),
          IdentifierChangeKind.fromAcr(Acr("urn:versola:acr:email-change")) == Some(IdentifierChangeKind.Email),
          IdentifierChangeKind.fromAcr(Acr("urn:versola:acr:other")) == None,
          IdentifierChangeKind.ReservedAcrs == Set(Acr("urn:versola:acr:phone-change"), Acr("urn:versola:acr:email-change")),
          IdentifierChangeKind.Phone.acr == Acr("urn:versola:acr:phone-change"),
          IdentifierChangeKind.Email.acr == Acr("urn:versola:acr:email-change"),
        )
      },
      test("is read from the account page's request by name") {
        assertTrue(
          IdentifierChangeKind.fromParameter("phone") == Some(IdentifierChangeKind.Phone),
          IdentifierChangeKind.fromParameter("email") == Some(IdentifierChangeKind.Email),
          IdentifierChangeKind.fromParameter("login") == None,
        )
      },
    ),
    suite("as stored on a conversation")(
      test("round-trips a phone change") {
        assertTrue(phoneChange.toJson.fromJson[IdentifierChange] == Right(phoneChange))
      },
      test("round-trips an email change in the verification stage") {
        assertTrue(emailChange.toJson.fromJson[IdentifierChange] == Right(emailChange))
      },
      test("is stored with readable names, so a deployment can be inspected") {
        assertTrue(
          phoneChange.toJson == """{"kind":"phone","value":"+77011234567","stage":"pending"}""",
          emailChange.toJson == """{"kind":"email","value":"new@example.com","stage":"verify_new"}""",
        )
      },
      test("refuses an unknown kind or stage") {
        assertTrue(
          """{"kind":"login","value":"x","stage":"pending"}""".fromJson[IdentifierChange].isLeft,
          """{"kind":"phone","value":"+77011234567","stage":"done"}""".fromJson[IdentifierChange].isLeft,
        )
      },
    ),
  )
