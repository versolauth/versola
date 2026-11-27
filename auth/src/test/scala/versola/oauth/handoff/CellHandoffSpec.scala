package versola.oauth.handoff

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.conversation.ConversationRouterSpec
import versola.oauth.model.{Nonce, State}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionRecord, SupersededSession, UserAgentId}
import versola.user.model.UserId
import versola.util.{Base64, CoreConfig, Email, JWT, MAC, Phone, RoutingKey, RsaKeyPair, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.UUID

/** The seal a sign-in is handed to another cell in (versolauth/versola#1046, #1050): what goes in it, and who may open it.
  *
  * Four cells take part. `kz-1` is the cell [[TestEnvConfig.coreConfig]] is a key of, `kz-2` (the home cell) has
  * [[TestEnvConfig.anotherCellKeyPair]], `kz-3` has a key of its own and is a cell every registry knows; `kz-9` has a key
  * and is known to nobody. */
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

  private def generate(keyId: String): RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId, pair.getPublic.asInstanceOf[RSAPublicKey], pair.getPrivate.asInstanceOf[RSAPrivateKey])

  private val thirdKeyPair = generate("kz-3-key")
  private val strangerKeyPair = generate("kz-9-key")

  /** The keys of the cells the registry lists. */
  private val listed: Map[String, RsaKeyPair] =
    Map("kz-1" -> TestEnvConfig.authKeyPair, "kz-2" -> TestEnvConfig.anotherCellKeyPair, "kz-3" -> thirdKeyPair)

  private val keyOf: Map[String, RsaKeyPair] = listed + ("kz-9" -> strangerKeyPair)

  private def registryOf(known: Map[String, RsaKeyPair] = listed, withoutKey: Set[String] = Set.empty): CellRegistry =
    new CellRegistry:
      override def keysOf(cell: String) = ZIO.succeed(known.get(cell).filterNot(_ => withoutKey(cell)).map(TestEnvConfig.publicKeysOf))
      override def encryptionKeyOf(cell: String) = ZIO.succeed(known.get(cell).filterNot(_ => withoutKey(cell)).map(_.publicKey))
      override def all = ZIO.succeed(Nil)
      override def refresh = ZIO.unit
      override def refreshAfterMiss = ZIO.unit

  /** A registry that learns of `late` only when it is read again after a miss, as an auth learns of a cell that was
    * registered since its last refresh; `refreshed` counts those reads. */
  private def registryLearning(late: String, refreshed: Ref[Int]): UIO[CellRegistry] =
    Ref.make(listed - late).map: known =>
      new CellRegistry:
        override def keysOf(cell: String) = known.get.map(_.get(cell).map(TestEnvConfig.publicKeysOf))
        override def encryptionKeyOf(cell: String) = known.get.map(_.get(cell).map(_.publicKey))
        override def all = ZIO.succeed(Nil)
        override def refresh = ZIO.unit
        override def refreshAfterMiss = refreshed.update(_ + 1) *> known.set(listed)

  /** The cell `cell`, under its own key; one that is none for `None`. `pair` puts another key in its place. */
  private def at(
      cell: Option[String],
      pair: Option[RsaKeyPair] = None,
      sessions: Stub[SessionRepository] = stub[SessionRepository],
      registry: CellRegistry = registryOf(),
  ): URIO[SecurityService & SecureRandom, CellHandoff] =
    for
      security <- ZIO.service[SecurityService]
      random <- ZIO.service[SecureRandom]
      keys = pair.orElse(cell.flatMap(keyOf.get)).getOrElse(TestEnvConfig.authKeyPair)
      config = TestEnvConfig.withKeyPairOf(TestEnvConfig.coreConfig, keys).copy(cellId = cell.map(RoutingKey.CellId.parse(_).toOption.get))
    yield CellHandoff.Impl(config, CellEnvelope.Impl(config, security, registry), random, sessions)

  /** A seal as a cell that does what it should not would make it: every part chosen by the caller, the signature made
    * properly under `signer` and the whole encrypted to `to`. */
  private def forged(
      signer: RsaKeyPair = TestEnvConfig.authKeyPair,
      issuer: String = "kz-1",
      audience: List[String] = List("kz-2"),
      from: String = "kz-1",
      to: String = "kz-2",
      expiresAt: Long = 60,
      ttl: Duration = 1.minute,
      algorithm: JWT.Algorithm = JWT.Algorithm.RS256,
      encryptedTo: RsaKeyPair = TestEnvConfig.anotherCellKeyPair,
      purpose: String = CellEnvelope.Purpose.Handoff,
  ): ZIO[SecurityService, Throwable, String] =
    val payload = CellHandoff.Payload(
      params = Map("client_id" -> List("c")),
      from = from,
      to = to,
      expiresAt = expiresAt,
      submission = None,
      superseded = None,
    )
    for
      security <- ZIO.service[SecurityService]
      jws <- JWT.serialize(
        claims = JWT.Claims(
          issuer,
          issuer,
          audience,
          Json.Obj("purpose" -> Json.Str(purpose), "body" -> Json.Str(Base64.urlEncode(payload.toJson.getBytes(StandardCharsets.UTF_8)))),
        ),
        ttl = ttl,
        signature = JWT.Signature.Asymmetric(algorithm, signer.keyId, signer.privateKey),
      )
      encrypted <- security.encryptRsaHybrid(jws.getBytes(StandardCharsets.UTF_8), encryptedTo.publicKey)
    yield RoutingKey.encode(home, Base64.urlEncode(encrypted))

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
        payload.from == "kz-1",
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
    test("a seal is opened only by the cell it is encrypted to, whatever key stands in front of it") {
      for
        first <- at(Some("kz-1"))
        third <- at(Some("kz-3"))
        ticket <- first.seal(record, Left(email), home)
        // a cell that holds another key cannot read it: the cell inside is the one the key was chosen for
        elsewhere <- third.open(ticket).either
        // the key in front is a hint for the router: changing it does not change who can read the seal
        relabelled <- third.open(ticket.replace("v1.kz-2.", "v1.kz-3.")).either
        notACell <- at(None).flatMap(_.open(ticket).either)
      yield assertTrue(
        elsewhere == Left(CellHandoff.Refused.Forged),
        relabelled == Left(CellHandoff.Refused.Forged),
        notACell == Left(CellHandoff.Refused.NotACell),
      )
    },
    test("a seal that was altered, or made under another key, or is not one, is refused") {
      for
        first <- at(Some("kz-1"))
        second <- at(Some("kz-2"))
        // `kz-1` with a key the registry does not list for it: not the cell it says
        stranger <- at(Some("kz-1"), pair = Some(strangerKeyPair))
        // the same key id as `kz-1`'s, another key
        impostor <- at(Some("kz-1"), pair = Some(strangerKeyPair.copy(keyId = TestEnvConfig.authKeyPair.keyId)))
        ticket <- first.seal(record, Left(email), home)
        altered <- second.open(ticket.dropRight(4) + "AAAA").either
        foreign <- stranger.seal(record, Left(email), home).flatMap(second.open(_).either)
        impersonated <- impostor.seal(record, Left(email), home).flatMap(second.open(_).either)
        garbage <- second.open("v1.kz-2.not base64!").either
        shortCiphertext <- second.open(RoutingKey.encode(home, Base64.urlEncode(Array.fill(10)(1.toByte)))).either
        bare <- second.open("").either
      yield assertTrue(
        altered == Left(CellHandoff.Refused.Forged),
        foreign == Left(CellHandoff.Refused.Forged),
        impersonated == Left(CellHandoff.Refused.Forged),
        garbage == Left(CellHandoff.Refused.Malformed),
        shortCiphertext == Left(CellHandoff.Refused.Forged),
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
          payload.from == "kz-1",
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
      test("is opened only by the cell it is encrypted to") {
        for
          first <- at(Some("kz-1"))
          third <- at(Some("kz-3"))
          ticket <- first.sealSubmission(record, home, cookie, None)
          elsewhere <- third.open(ticket).either
        yield assertTrue(elsewhere == Left(CellHandoff.Refused.Forged))
      },
    ),
    // versolauth/versola#1050: each cell has a key of its own; nothing is shared between them.
    suite("who made a seal, and for whom")(
      test("a seal by cell A for cell B opens in B, and names A") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          fromFirst <- first.seal(record, Left(email), home).flatMap(second.open)
          // and the other way round: the same code, the roles swapped
          back <- second.seal(record, Left(email), RoutingKey.CellId.parse("kz-1").toOption.get).flatMap(first.open)
        yield assertTrue(fromFirst.from == "kz-1", fromFirst.to == "kz-2", back.from == "kz-2", back.to == "kz-1")
      },
      test("a seal made by a cell the home cell does not know is refused, though it is well made") {
        for
          stranger <- at(Some("kz-9"))
          second <- at(Some("kz-2"))
          ticket <- stranger.seal(record, Left(email), home)
          refused <- second.open(ticket).either
        yield assertTrue(refused == Left(CellHandoff.Refused.Forged))
      },
      test("a cell that does not know another's key does not take its seal, and the registry is consulted by the issuer's name") {
        for
          first <- at(Some("kz-1"))
          // the home cell's registry has lost kz-1
          second <- at(Some("kz-2"), registry = registryOf(known = listed - "kz-1"))
          noKey <- at(Some("kz-2"), registry = registryOf(withoutKey = Set("kz-1")))
          ticket <- first.seal(record, Left(email), home)
          unknown <- second.open(ticket).either
          keyless <- noKey.open(ticket).either
        yield assertTrue(unknown == Left(CellHandoff.Refused.Forged), keyless == Left(CellHandoff.Refused.Forged))
      },
      test("a seal for the home cell that is encrypted to another cell's key cannot be read by the home cell") {
        for
          second <- at(Some("kz-2"))
          ticket <- forged(encryptedTo = thirdKeyPair)
          refused <- second.open(ticket).either
        yield assertTrue(refused == Left(CellHandoff.Refused.Forged))
      },
      test("a seal whose audience names another cell is refused as sealed for another cell") {
        for
          second <- at(Some("kz-2"))
          other <- forged(audience = List("kz-3"))
          none <- forged(audience = Nil)
          several <- forged(audience = List("kz-3", "kz-2"))
          wrongAudience <- second.open(other).either
          noAudience <- second.open(none).either
          withHome <- second.open(several).either
        yield assertTrue(
          wrongAudience == Left(CellHandoff.Refused.Elsewhere),
          // a seal that names nobody is not one a cell makes
          noAudience.isLeft,
          withHome.isRight,
        )
      },
      test("a seal whose payload names another cell is refused as sealed for another cell") {
        for
          second <- at(Some("kz-2"))
          ticket <- forged(to = "kz-3")
          refused <- second.open(ticket).either
        yield assertTrue(refused == Left(CellHandoff.Refused.Elsewhere))
      },
      test("a seal whose payload says another cell made it than the one whose key signed it is refused") {
        for
          second <- at(Some("kz-2"))
          // kz-1 signs, and the payload says kz-3 sealed it
          ticket <- forged(from = "kz-3")
          // kz-1 signs, and says kz-3 is the issuer: kz-3's keys do not verify it
          claimed <- forged(issuer = "kz-3", from = "kz-3")
          // kz-3 signs as itself but the payload says kz-1: a cell cannot make a seal in another's name
          inOthersName <- forged(signer = thirdKeyPair, issuer = "kz-3", from = "kz-1")
          differs <- second.open(ticket).either
          notVerified <- second.open(claimed).either
          named <- second.open(inOthersName).either
        yield assertTrue(
          differs == Left(CellHandoff.Refused.Forged),
          notVerified == Left(CellHandoff.Refused.Forged),
          named == Left(CellHandoff.Refused.Forged),
        )
      },
      test("an envelope made for another use is not a handoff, and a handoff is not accepted where another use is expected") {
        for
          first <- at(Some("kz-1"))
          second <- at(Some("kz-2"))
          security <- ZIO.service[SecurityService]
          opener = CellEnvelope.Impl(
            TestEnvConfig.withKeyPairOf(TestEnvConfig.coreConfig, TestEnvConfig.anotherCellKeyPair).copy(cellId = Some(home)),
            security,
            registryOf(),
          )
          // a user-move envelope made by kz-1 for kz-2, with a body that would be a valid handoff payload
          moveEnvelope <- forged(purpose = CellEnvelope.Purpose.UserMove)
          asHandoff <- second.open(moveEnvelope).either
          // and a genuine handoff, presented where a moved user is expected
          handoff <- first.seal(record, Left(email), home)
          asMove <- opener.open(CellEnvelope.Purpose.UserMove, handoff).either
          asHandoffStill <- opener.open(CellEnvelope.Purpose.Handoff, handoff).either
        yield assertTrue(
          asHandoff == Left(CellHandoff.Refused.Purpose),
          asMove == Left(CellEnvelope.Refused.WrongPurpose),
          asHandoffStill.isRight,
        )
      },
      test("a seal signed with anything but RS256 is refused, as is one signed with a key the issuer has not got") {
        for
          second <- at(Some("kz-2"))
          ps256 <- forged(algorithm = JWT.Algorithm.PS256)
          wrongKey <- forged(signer = strangerKeyPair)
          sameKid <- forged(signer = strangerKeyPair.copy(keyId = TestEnvConfig.authKeyPair.keyId))
          one <- second.open(ps256).either
          two <- second.open(wrongKey).either
          three <- second.open(sameKid).either
        yield assertTrue(
          one == Left(CellHandoff.Refused.Forged),
          two == Left(CellHandoff.Refused.Forged),
          three == Left(CellHandoff.Refused.Forged),
        )
      },
      test("a seal expired in its signature is refused, whatever its payload says; so is one expired in its payload") {
        for
          second <- at(Some("kz-2"))
          signature <- forged(ttl = Duration.fromSeconds(-60), expiresAt = 3600)
          payload <- forged(expiresAt = 0)
          fresh <- forged()
          one <- second.open(signature).either
          two <- second.open(payload).either
          three <- second.open(fresh).either
        yield assertTrue(one == Left(CellHandoff.Refused.Expired), two == Left(CellHandoff.Refused.Expired), three.isRight)
      },
      test("a cell registered since the last refresh is sealed for, and opened from, after the cells are read once more") {
        for
          sealerReads <- Ref.make(0)
          openerReads <- Ref.make(0)
          // kz-1 does not know kz-2 yet when it seals, and kz-2 does not know kz-1 yet when it opens.
          sealerRegistry <- registryLearning(late = "kz-2", refreshed = sealerReads)
          openerRegistry <- registryLearning(late = "kz-1", refreshed = openerReads)
          first <- at(Some("kz-1"), registry = sealerRegistry)
          second <- at(Some("kz-2"), registry = openerRegistry)
          ticket <- first.seal(record, Left(email), home)
          payload <- second.open(ticket)
          readsToSeal <- sealerReads.get
          readsToOpen <- openerReads.get
        yield assertTrue(payload.from == "kz-1", readsToSeal == 1, readsToOpen == 1)
      },
      test("a seal for a cell that has no key is not made: a typed error, and nothing is read for it") {
        val sessions = stub[SessionRepository]
        val nobody = RoutingKey.CellId.parse("kz-9").toOption.get
        val keyless = RoutingKey.CellId.parse("kz-3").toOption.get
        for
          first <- at(Some("kz-1"), sessions = sessions)
          noKeyAtAll <- first.seal(withPrior, Left(email), nobody).either
          noKeyListed <- at(Some("kz-1"), sessions = sessions, registry = registryOf(withoutKey = Set("kz-3")))
            .flatMap(_.sealSubmission(withPrior, keyless, cookie, None).either)
        yield assertTrue(
          noKeyAtAll == Left(CellHandoff.NoKey("kz-9")),
          noKeyListed == Left(CellHandoff.NoKey("kz-3")),
          sessions.findSession.calls.isEmpty,
        )
      },
      test("an auth that is no cell does not seal") {
        for
          plain <- at(None)
          result <- plain.seal(record, Left(email), home).either
        yield assertTrue(result.isLeft, !result.left.exists(_.isInstanceOf[CellHandoff.NoKey]))
      },
      test("a typical seal fits the limit with room to spare, and so does a passkey request") {
        val passkey =
          Some("""{"challenge":"yBO3RR9k3zWpZQ1p0y2v8hLWZ0fE5jvD0cT3qk0jVqQ","rpId":"example.com","timeout":60000,"userVerification":"preferred"}""")
        for
          first <- at(Some("kz-1"))
          address <- first.seal(record, Left(email), home)
          submission <- first.sealSubmission(record, home, cookie, passkey)
        yield assertTrue(address.length < CellHandoff.MaxLength / 2, submission.length < CellHandoff.MaxLength / 2)
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
