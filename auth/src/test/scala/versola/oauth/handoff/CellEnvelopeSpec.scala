package versola.oauth.handoff

import versola.auth.TestEnvConfig
import versola.util.{RoutingKey, RsaKeyPair, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets

/** What one cell seals for another (versolauth/versola#1050, #1051): the bytes arrive only where they were sent, from the cell
  * that made them, for the use they were made for. The handoff's own rules are in `CellHandoffSpec`. */
object CellEnvelopeSpec extends UnitSpecBase:

  private val one = RoutingKey.CellId.parse("kz-1").toOption.get
  private val two = RoutingKey.CellId.parse("kz-2").toOption.get
  private val three = RoutingKey.CellId.parse("kz-3").toOption.get

  private val keys: Map[String, RsaKeyPair] = Map("kz-1" -> TestEnvConfig.authKeyPair, "kz-2" -> TestEnvConfig.anotherCellKeyPair)

  private def registry(known: Map[String, RsaKeyPair] = keys): CellRegistry =
    new CellRegistry:
      override def keysOf(cell: String) = ZIO.succeed(known.get(cell).map(TestEnvConfig.publicKeysOf))
      override def encryptionKeyOf(cell: String) = ZIO.succeed(known.get(cell).map(_.publicKey))
      override def all = ZIO.succeed(Nil)
      override def refresh = ZIO.unit
      override def refreshAfterMiss = ZIO.unit

  private def at(cell: Option[RoutingKey.CellId], known: Map[String, RsaKeyPair] = keys): URIO[SecurityService, CellEnvelope] =
    ZIO.service[SecurityService].map: security =>
      val pair = cell.flatMap(id => keys.get(id)).getOrElse(TestEnvConfig.authKeyPair)
      CellEnvelope.Impl(TestEnvConfig.withKeyPairOf(TestEnvConfig.coreConfig, pair).copy(cellId = cell), security, registry(known))

  private val content = "a user, as a document".getBytes(StandardCharsets.UTF_8)

  def spec = suite("CellEnvelope")(
    test("what a cell seals for another is opened there, with the name of the cell that made it, for the purpose it was made for") {
      for
        first <- at(Some(one))
        second <- at(Some(two))
        move <- first.seal(two, CellEnvelope.Purpose.UserMove, content)
        handoff <- first.seal(two, CellEnvelope.Purpose.Handoff, content)
        movedBack <- second.seal(one, CellEnvelope.Purpose.UserMove, content).flatMap(first.open(CellEnvelope.Purpose.UserMove, _))
        opened <- second.open(CellEnvelope.Purpose.UserMove, move)
        handed <- second.open(CellEnvelope.Purpose.Handoff, handoff)
      yield assertTrue(
        opened._1 == "kz-1",
        String(opened._2, StandardCharsets.UTF_8) == "a user, as a document",
        handed._1 == "kz-1",
        movedBack._1 == "kz-2",
        // the routing key of the cell it is for is in front, so that what is in front of the cells sends it there
        RoutingKey.decode(move).exists(_.cell.contains(two)),
      )
    },
    test("an envelope made for one use is refused for another, in both directions, whoever made it") {
      for
        first <- at(Some(one))
        second <- at(Some(two))
        move <- first.seal(two, CellEnvelope.Purpose.UserMove, content)
        handoff <- first.seal(two, CellEnvelope.Purpose.Handoff, content)
        moveAsHandoff <- second.open(CellEnvelope.Purpose.Handoff, move).either
        handoffAsMove <- second.open(CellEnvelope.Purpose.UserMove, handoff).either
        unknown <- second.open("something-else", move).either
        registration <- first.seal(two, CellEnvelope.Purpose.Registration, content)
        registrationAsHandoff <- second.open(CellEnvelope.Purpose.Handoff, registration).either
        registrationAsMove <- second.open(CellEnvelope.Purpose.UserMove, registration).either
        handoffAsRegistration <- second.open(CellEnvelope.Purpose.Registration, handoff).either
        moveAsRegistration <- second.open(CellEnvelope.Purpose.Registration, move).either
        registrationOpened <- second.open(CellEnvelope.Purpose.Registration, registration).either
      yield assertTrue(
        registrationAsHandoff == Left(CellEnvelope.Refused.WrongPurpose),
        registrationAsMove == Left(CellEnvelope.Refused.WrongPurpose),
        handoffAsRegistration == Left(CellEnvelope.Refused.WrongPurpose),
        moveAsRegistration == Left(CellEnvelope.Refused.WrongPurpose),
        registrationOpened.isRight,
        CellEnvelope.Purpose.lifetimeOf(CellEnvelope.Purpose.Registration) == Some(1.minute),
        moveAsHandoff == Left(CellEnvelope.Refused.WrongPurpose),
        handoffAsMove == Left(CellEnvelope.Refused.WrongPurpose),
        unknown == Left(CellEnvelope.Refused.WrongPurpose),
      )
    },
    test("an envelope is opened only by the cell it was sealed for") {
      for
        first <- at(Some(one))
        second <- at(Some(two))
        // sealed to the key of kz-2, and presented to kz-1: it cannot even be read there
        forMe <- first.seal(two, CellEnvelope.Purpose.UserMove, content)
        readByTheSealer <- first.open(CellEnvelope.Purpose.UserMove, forMe).either
        // and one whose audience is another cell, though readable here, is refused as sealed for another
        stranger <- at(Some(three), known = keys + ("kz-3" -> TestEnvConfig.impostorKeyPair))
        wrongAudience <- stranger.seal(two, CellEnvelope.Purpose.UserMove, content).flatMap(second.open(CellEnvelope.Purpose.UserMove, _)).either
      yield assertTrue(readByTheSealer == Left(CellEnvelope.Refused.Forged), wrongAudience == Left(CellEnvelope.Refused.Forged))
    },
    test("an envelope from a cell the opener does not know, or altered, or not one, is refused") {
      for
        first <- at(Some(one))
        ignorant <- at(Some(two), known = Map("kz-2" -> TestEnvConfig.anotherCellKeyPair))
        second <- at(Some(two))
        text <- first.seal(two, CellEnvelope.Purpose.UserMove, content)
        unknownSealer <- ignorant.open(CellEnvelope.Purpose.UserMove, text).either
        altered <- second.open(CellEnvelope.Purpose.UserMove, text.dropRight(4) + "AAAA").either
        garbage <- second.open(CellEnvelope.Purpose.UserMove, "v1.kz-2.").either
        notRouted <- second.open(CellEnvelope.Purpose.UserMove, "v1..x").either
      yield assertTrue(
        unknownSealer == Left(CellEnvelope.Refused.Forged),
        altered == Left(CellEnvelope.Refused.Forged),
        garbage == Left(CellEnvelope.Refused.Malformed),
        notRouted == Left(CellEnvelope.Refused.Malformed),
      )
    },
    test("an envelope lives as long as its purpose says, and no longer") {
      for
        first <- at(Some(one))
        second <- at(Some(two))
        move <- first.seal(two, CellEnvelope.Purpose.UserMove, content)
        handoff <- first.seal(two, CellEnvelope.Purpose.Handoff, content)
        _ <- TestClock.adjust(2.minutes)
        movedLater <- second.open(CellEnvelope.Purpose.UserMove, move).either
        handoffLater <- second.open(CellEnvelope.Purpose.Handoff, handoff).either
        _ <- TestClock.adjust(10.minutes)
        movedMuchLater <- second.open(CellEnvelope.Purpose.UserMove, move).either
      yield assertTrue(movedLater.isRight, handoffLater == Left(CellEnvelope.Refused.Expired), movedMuchLater == Left(CellEnvelope.Refused.Expired))
    },
    test("nothing is sealed for a cell with no key, or by an auth that is no cell, or for a purpose that is not known") {
      for
        first <- at(Some(one))
        plain <- at(None)
        made <- Ref.make(false)
        noKey <- first.sealMade(three, CellEnvelope.Purpose.UserMove, made.set(true).as(content)).either
        notACell <- plain.seal(two, CellEnvelope.Purpose.UserMove, content).either
        unknown <- first.seal(two, "something-else", content).either
        opened <- plain.open(CellEnvelope.Purpose.UserMove, "v1.kz-2.AAAA").either
        read <- made.get
      yield assertTrue(
        noKey == Left(CellEnvelope.NoKey("kz-3")),
        // the content is made only once the cell is known to have a key
        !read,
        notACell == Left(CellEnvelope.NotACell),
        unknown.isLeft,
        opened == Left(CellEnvelope.Refused.NotACell),
      )
    },
  ).provide(SecureRandom.live, SecurityService.live)
