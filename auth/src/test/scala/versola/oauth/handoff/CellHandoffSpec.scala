package versola.oauth.handoff

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.conversation.ConversationRouterSpec
import versola.oauth.model.{Nonce, State}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionRecord, SupersededSession, UserAgentId}
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, MAC, Phone, RoutingKey, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** The seal a sign-in is handed to another cell in (versolauth/versola#1046): what goes in it, and who may open it. */
object CellHandoffSpec extends UnitSpecBase:

  private val home = RoutingKey.CellId.parse("kz-2").toOption.get
  private val record = ConversationRouterSpec.initialRecord.copy(nonce = Some(Nonce("n-1")), promptConsent = true, uiLocales = Some(List("ru", "kk")))
  private val email = Email("user@example.com")

  private val cookie = "conversation-cookie-content"

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
  private val priorMac = MAC(Array.fill(32)(7.toByte))

  private val priorSession = SessionRecord(
    userId = userId,
    clients = List(ClientEntry(record.clientId, Instant.EPOCH)),
    userAgentId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000b1")),
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = PublicSessionId("kz-1.first-cells-session"),
    expiresAt = Instant.EPOCH,
  )

  /** A sign-in that was started in a cell by a browser that had a session there. */
  private val withPrior = record.copy(priorSessionId = Some(priorMac), priorSessionUserId = Some(userId))

  private def at(
      cell: Option[String],
      config: CoreConfig = TestEnvConfig.coreConfig,
      sessions: Stub[SessionRepository] = stub[SessionRepository],
  ): URIO[SecurityService & SecureRandom, CellHandoff] =
    for
      security <- ZIO.service[SecurityService]
      random <- ZIO.service[SecureRandom]
    yield CellHandoff.Impl(config.copy(cellId = cell.map(RoutingKey.CellId.parse(_).toOption.get)), security, random, sessions)

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
    test("an address seal carries no submission") {
      for
        first <- at(Some("kz-1"))
        second <- at(Some("kz-2"))
        ticket <- first.seal(record, Left(email), home)
        payload <- second.open(ticket)
      yield assertTrue(payload.submission.isEmpty)
    },
    suite("a submission seal")(
      test("round-trips with the digest of the cookie, the passkey request and no login hint") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          ticket <- first.sealSubmission(record, home, cookie, Some("""{"challenge":"c"}"""))
          payload <- second.open(ticket)
        yield assertTrue(
          ticket.startsWith("v1.kz-2."),
          payload.to == "kz-2",
          payload.submission.map(_.cookie) == Some(CellHandoff.cookieDigest(cookie)),
          payload.submission.flatMap(_.passkeyRequest) == Some("""{"challenge":"c"}"""),
          payload.submission.exists(_.id.nonEmpty),
          payload.params("client_id") == List(record.clientId: String),
          !payload.params.contains("login_hint"),
        )
      },
      test("carries no passkey request for a login and password") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          payload <- first.sealSubmission(record, home, cookie, None).flatMap(second.open)
        yield assertTrue(payload.submission.exists(_.passkeyRequest.isEmpty))
      },
      test("does not carry the cookie itself") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          payload <- first.sealSubmission(record, home, cookie, None).flatMap(second.open)
        yield assertTrue(!payload.submission.exists(_.cookie.contains(cookie)))
      },
      test("is not made for a request too large to travel in a URL, and neither is an address seal") {
        val large = record.copy(state = Some(State("s" * CellHandoff.MaxLength)))
        for
          first <- at(Some("kz-1"))
          submission <- first.sealSubmission(large, home, cookie, None).either
          passkey <- first.sealSubmission(record, home, cookie, Some("r" * CellHandoff.MaxLength)).either
          address <- first.seal(large, Left(email), home).either
          fits <- first.sealSubmission(record, home, cookie, Some("""{"challenge":"c"}""")).either
        yield assertTrue(
          submission.left.exists(_.isInstanceOf[CellHandoff.TooLarge]),
          passkey.left.exists(_.isInstanceOf[CellHandoff.TooLarge]),
          address.left.exists(_.isInstanceOf[CellHandoff.TooLarge]),
          fits.exists(_.length <= CellHandoff.MaxLength),
        )
      },
      test("two seals of the same submission have different ids") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          one <- first.sealSubmission(record, home, cookie, None).flatMap(second.open)
          two <- first.sealSubmission(record, home, cookie, None).flatMap(second.open)
        yield assertTrue(one.submission.map(_.id) != two.submission.map(_.id))
      },
      test("sameCookie accepts the cookie sealed and refuses another") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          payload <- first.sealSubmission(record, home, cookie, None).flatMap(second.open)
          submission = payload.submission.get
        yield assertTrue(
          CellHandoff.sameCookie(submission, cookie),
          !CellHandoff.sameCookie(submission, cookie + "x"),
          !CellHandoff.sameCookie(submission, ""),
        )
      },
      test("is opened only by the cell it names") {
        for
          first <- at(Some("kz-1"))
          third <- at(Some("kz-3"))
          ticket <- first.sealSubmission(record, home, cookie, None)
          elsewhere <- third.open(ticket).either
        yield assertTrue(elsewhere == Left(CellHandoff.Refused.Elsewhere))
      },
    ),
    // versolauth/versola#1048: the session the browser had in the first cell travels in the seal.
    suite("the session the sign-in replaces")(
      test("an address seal names the prior session of the first cell by its public id and its user") {
        val sessions = stub[SessionRepository]
        for
          _ <- sessions.findSession.succeedsWith(Some(priorSession))
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          payload <- first.seal(withPrior, Left(email), home).flatMap(second.open)
        yield assertTrue(
          payload.superseded == Some(SupersededSession(userId = userId, sessionId = priorSession.publicId)),
          sessions.findSession.calls.size == 1,
        )
      },
      test("a submission seal names it too") {
        val sessions = stub[SessionRepository]
        for
          _ <- sessions.findSession.succeedsWith(Some(priorSession))
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          payload <- first.sealSubmission(withPrior, home, cookie, None).flatMap(second.open)
        yield assertTrue(payload.superseded == Some(SupersededSession(userId = userId, sessionId = priorSession.publicId)))
      },
      test("the session id is looked up by the record's prior session, and the seal does not carry the MAC") {
        val sessions = stub[SessionRepository]
        for
          _ <- sessions.findSession.succeedsWith(Some(priorSession))
          first <- at(Some("kz-1"), sessions = sessions)
          ticket <- first.seal(withPrior, Left(email), home)
        yield assertTrue(
          sessions.findSession.calls.size == 1,
          java.util.Arrays.equals(sessions.findSession.calls.head: Array[Byte], priorMac: Array[Byte]),
        )
      },
      test("a record that already carries a superseded session carries that one, and the repository is not asked") {
        val sessions = stub[SessionRepository]
        val handed = SupersededSession(userId = userId, sessionId = PublicSessionId("kz-0.sessions-of-another-cell"))
        val handedRecord = withPrior.copy(supersededSessionId = Some(handed.sessionId), supersededSessionUserId = Some(handed.userId))
        for
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          address <- first.seal(handedRecord, Left(email), home).flatMap(second.open)
          submission <- first.sealSubmission(handedRecord, home, cookie, None).flatMap(second.open)
        yield assertTrue(
          address.superseded == Some(handed),
          submission.superseded == Some(handed),
          sessions.findSession.calls.isEmpty,
        )
      },
      test("a record with no prior session carries none, and the repository is not asked") {
        val sessions = stub[SessionRepository]
        for
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          address <- first.seal(record, Left(email), home).flatMap(second.open)
          submission <- first.sealSubmission(record, home, cookie, None).flatMap(second.open)
        yield assertTrue(address.superseded.isEmpty, submission.superseded.isEmpty, sessions.findSession.calls.isEmpty)
      },
      test("a prior session the repository no longer knows gives none, and the seal is made") {
        val sessions = stub[SessionRepository]
        for
          _ <- sessions.findSession.succeedsWith(None)
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          payload <- first.seal(withPrior, Left(email), home).flatMap(second.open)
        yield assertTrue(payload.superseded.isEmpty)
      },
      test("a repository that fails gives none, and the seal is still made") {
        val sessions = stub[SessionRepository]
        for
          _ <- sessions.findSession.failsWith(RuntimeException("database is down"))
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          address <- first.seal(withPrior, Left(email), home).flatMap(second.open)
          submission <- first.sealSubmission(withPrior, home, cookie, None).flatMap(second.open)
        yield assertTrue(address.superseded.isEmpty, submission.superseded.isEmpty, address.params("client_id") == List(record.clientId: String))
      },
      test("a prior session id without a user gives none: a session id is not acted on without its user") {
        val sessions = stub[SessionRepository]
        for
          _ <- sessions.findSession.succeedsWith(Some(priorSession))
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          payload <- first.seal(withPrior.copy(priorSessionUserId = None), Left(email), home).flatMap(second.open)
        yield assertTrue(payload.superseded.isEmpty)
      },
      test("a record with only half of a superseded session falls back to its own prior session") {
        val sessions = stub[SessionRepository]
        val half = withPrior.copy(supersededSessionId = Some(PublicSessionId("kz-0.x")), supersededSessionUserId = None)
        for
          _ <- sessions.findSession.succeedsWith(Some(priorSession))
          first <- at(Some("kz-1"), sessions = sessions)
          second <- at(Some("kz-2"))
          payload <- first.seal(half, Left(email), home).flatMap(second.open)
        yield assertTrue(payload.superseded == Some(SupersededSession(userId = userId, sessionId = priorSession.publicId)))
      },
      test("a seal made under the old shape of the payload, with no superseded field, is not read as having one") {
        val old = """{"params":{},"to":"kz-2","expiresAt":1,"submission":null}"""
        assertTrue(old.fromJson[CellHandoff.Payload].isLeft || old.fromJson[CellHandoff.Payload].exists(_.superseded.isEmpty))
      },
    ),
    suite("SupersededSession")(
      test("round-trips through JSON as the user id and the session id") {
        val session = SupersededSession(userId = userId, sessionId = PublicSessionId("kz-1.first-cells-session"))
        val json = session.toJson
        assertTrue(
          json.fromJson[SupersededSession] == Right(session),
          json.fromJson[zio.json.ast.Json] == Right(
            zio.json.ast.Json.Obj(
              "userId" -> zio.json.ast.Json.Str(userId.toString),
              "sessionId" -> zio.json.ast.Json.Str("kz-1.first-cells-session"),
            ),
          ),
        )
      },
      test("is not read from a body that lacks the user") {
        assertTrue("""{"sessionId":"kz-1.s"}""".fromJson[SupersededSession].isLeft)
      },
    ),
    suite("paramsOf")(
      test("has no login_hint without a credential") {
        assertTrue(!CellHandoff.paramsOf(record, None).contains("login_hint"))
      },
      test("has the address as login_hint with a credential") {
        assertTrue(CellHandoff.paramsOf(record, Some(Left(email)))("login_hint") == List("user@example.com"))
      },
    ),
  ).provide(SecureRandom.live, SecurityService.live)
