package versola.oauth.handoff

import versola.auth.TestEnvConfig
import versola.oauth.conversation.ConversationRouterSpec
import versola.oauth.model.{Nonce, State}
import versola.util.{CoreConfig, Email, Phone, RoutingKey, SecureRandom, SecurityService}
import zio.*
import zio.test.*

import javax.crypto.spec.SecretKeySpec

/** The seal a sign-in is handed to another cell in (versolauth/versola#1046): what goes in it, and who may open it. */
object CellHandoffSpec extends ZIOSpecDefault:

  private val home = RoutingKey.CellId.parse("kz-2").toOption.get
  private val record = ConversationRouterSpec.initialRecord.copy(nonce = Some(Nonce("n-1")), promptConsent = true, uiLocales = Some(List("ru", "kk")))
  private val email = Email("user@example.com")

  private def at(cell: Option[String], config: CoreConfig = TestEnvConfig.coreConfig): URIO[SecurityService, CellHandoff] =
    ZIO.serviceWith[SecurityService](CellHandoff.Impl(config.copy(cellId = cell.map(RoutingKey.CellId.parse(_).toOption.get)), _))

  def spec = suite("CellHandoff")(
    test("the home cell opens what another cell sealed for it: the request's parameters and the address as the login hint") {
      for
        first <- at(Some("kz-1"))
        second <- at(Some("kz-2"))
        ticket <- first.seal(record, Left(email), home)
        payload <- second.open(ticket)
      yield assertTrue(
        ticket.startsWith("v1.kz-2."),
        // the address is not readable in what the browser carries
        !ticket.contains("user") && !new String(
          java.util.Base64.getUrlDecoder.decode(RoutingKey.strip(ticket)),
          "ISO-8859-1",
        ).contains("user@example.com"),
        payload.to == "kz-2",
        payload.params("client_id") == List(record.clientId: String),
        payload.params("redirect_uri") == List(record.redirectUri.encode),
        payload.params("response_type") == List("code"),
        payload.params("state") == record.state.toList.map(identity[String]),
        payload.params("code_challenge") == record.codeChallenge.toList.map(identity[String]),
        payload.params("code_challenge_method") == List("S256"),
        payload.params("nonce") == List("n-1"),
        payload.params("prompt") == List("consent"),
        payload.params("ui_locales") == List("ru kk"),
        payload.params("login_hint") == List("user@example.com"),
        !payload.params.contains("claims") && !payload.params.contains("authorization_details") && !payload.params.contains("resource"),
      )
    },
    test("a phone is carried as the login hint in the form it is stored in") {
      for
        first <- at(Some("kz-1"))
        second <- at(Some("kz-2"))
        ticket <- first.seal(record, Right(Phone("+15551234567")), home)
        payload <- second.open(ticket)
      yield assertTrue(payload.params("login_hint") == List("+15551234567"))
    },
    test("a seal is opened only by the cell it names, whatever key stands in front of it") {
      for
        first <- at(Some("kz-1"))
        third <- at(Some("kz-3"))
        ticket <- first.seal(record, Left(email), home)
        elsewhere <- third.open(ticket).either
        // the key in front is a hint for the router: changing it does not change who the seal is for
        relabelled <- third.open(ticket.replace("v1.kz-2.", "v1.kz-3.")).either
        notACell <- at(None).flatMap(_.open(ticket).either)
      yield assertTrue(
        elsewhere == Left(CellHandoff.Refused.Elsewhere),
        relabelled == Left(CellHandoff.Refused.Elsewhere),
        notACell == Left(CellHandoff.Refused.NotACell),
      )
    },
    test("a seal that was altered, or made under another key, or is not one, is refused") {
      val otherKey = TestEnvConfig.coreConfig.copy(central =
        TestEnvConfig.coreConfig.central.copy(secretKey = SecretKeySpec(Array.fill(32)(9.toByte), "HmacSHA256")),
      )
      for
        first <- at(Some("kz-1"))
        second <- at(Some("kz-2"))
        stranger <- at(Some("kz-1"), otherKey)
        ticket <- first.seal(record, Left(email), home)
        altered <- second.open(ticket.dropRight(4) + "AAAA").either
        foreign <- stranger.seal(record, Left(email), home).flatMap(second.open(_).either)
        garbage <- second.open("v1.kz-2.not base64!").either
        bare <- second.open("").either
      yield assertTrue(
        altered == Left(CellHandoff.Refused.Forged),
        foreign == Left(CellHandoff.Refused.Forged),
        garbage == Left(CellHandoff.Refused.Malformed),
        bare.isLeft,
      )
    },
    test("a seal lives a minute") {
      for
        first <- at(Some("kz-1"))
        second <- at(Some("kz-2"))
        ticket <- first.seal(record, Left(email), home)
        _ <- TestClock.adjust(59.seconds)
        inTime <- second.open(ticket).either
        _ <- TestClock.adjust(1.second)
        late <- second.open(ticket).either
      yield assertTrue(inTime.isRight, late == Left(CellHandoff.Refused.Expired))
    },
  ).provide(SecureRandom.live >>> SecurityService.live)
