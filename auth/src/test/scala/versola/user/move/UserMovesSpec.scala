package versola.user.move

import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken}
import versola.oauth.handoff.{CellEnvelope, CellRegistry}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.user.move.UserMoveRepository.{Completed, Written}
import versola.user.move.UserMoveRepositorySpec.{documentOne, moveOne, moveTwo, now, userOne, userTwo}
import versola.util.audit.{AuditDraft, AuditOutcome}
import versola.util.{RoutingKey, RsaKeyPair, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.json.*
import zio.metrics.Metric
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.UUID

/** A user's move as the two cells say it (versolauth/versola#1051): `kz-1` is the cell the user leaves, `kz-2` the one they
  * arrive in, and the envelope between them is the real one. */
object UserMovesSpec extends UnitSpecBase:

  private val one = RoutingKey.CellId.parse("kz-1").toOption.get
  private val two = RoutingKey.CellId.parse("kz-2").toOption.get
  private val keys: Map[String, RsaKeyPair] = Map("kz-1" -> TestEnvConfig.authKeyPair, "kz-2" -> TestEnvConfig.anotherCellKeyPair)

  private val userId = UserId(userOne)

  private def registry(known: Map[String, RsaKeyPair]): CellRegistry =
    new CellRegistry:
      override def keysOf(cell: String) = ZIO.succeed(known.get(cell).map(TestEnvConfig.publicKeysOf))
      override def encryptionKeyOf(cell: String) = ZIO.succeed(known.get(cell).map(_.publicKey))
      override def all = ZIO.succeed(Nil)
      override def refresh = ZIO.unit
      override def refreshAfterMiss = ZIO.unit

  /** One cell, with the collaborators of the service as stubs and an audit log of its own. */
  private class Cell(id: Option[RoutingKey.CellId], known: Map[String, RsaKeyPair] = keys):
    val users = stub[UserRepository]
    val departures = stub[UserDepartureRepository]
    val moves = stub[UserMoveRepository]
    val configuration = stub[OAuthConfigurationService]
    val audit = CapturingAuditEvents.shared()
    def service: URIO[SecurityService, UserMoves] =
      ZIO.service[SecurityService].map: security =>
        val pair = id.flatMap(cell => keys.get(cell)).getOrElse(TestEnvConfig.authKeyPair)
        val config = TestEnvConfig.withKeyPairOf(TestEnvConfig.coreConfig, pair).copy(cellId = id)
        UserMoves.make(config, users, departures, moves, CellEnvelope.Impl(config, security, registry(known)), audit, configuration)

  private def departure(to: String, state: DepartureState) =
    UserDeparture(userId = userId, toCell = to, moveId = moveOne, state = state, at = now)

  /** A client the rows of `documentOne` name, with the scopes its refresh tokens carry. */
  private val app: OAuthClientRecord =
    TestEnvConfig.mtlsClient(ClientId("app")).copy(scope = Set(ScopeToken("openid"), ScopeToken("offline_access"), ScopeToken("profile")))

  /** A cell that knows the tenants and the client the rows of `documentOne` name. */
  private def knowing(cell: Cell): UIO[Unit] =
    cell.configuration.findByTenant.succeedsWith(Vector(app)) *> cell.configuration.find.succeedsWith(Some(app))

  private def audit(drafts: List[AuditDraft], kind: versola.util.audit.AuditKind) = drafts.filter(_.kind == kind)

  private def audited(drafts: List[AuditDraft], kind: versola.util.audit.AuditKind, details: versola.util.audit.AuditDetails) =
    drafts.size == 1 && drafts.head.kind == kind && drafts.head.userId.contains(userId) && drafts.head.outcome == AuditOutcome.success &&
      drafts.head.actor.contains((AuthActors.service, Some("central"))) && drafts.head.details == details

  /** `kz-1` exports `documentOne` for `kz-2`; the envelope is what `kz-2` is asked to take in. */
  private def exported(first: Cell, document: UserMoveDocument = documentOne): ZIO[SecurityService, Throwable | MoveError, UserMoves.Exported] =
    first.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Leaving))) *>
      first.moves.read.succeedsWith(Some(document)) *>
      first.service.flatMap(_.exportUser(userId))

  private def envelopeOf(content: String, purpose: String = CellEnvelope.Purpose.UserMove) =
    ZIO.service[SecurityService].flatMap: security =>
      val config = TestEnvConfig.withKeyPairOf(TestEnvConfig.coreConfig, TestEnvConfig.authKeyPair).copy(cellId = Some(one))
      CellEnvelope.Impl(config, security, registry(keys)).seal(two, purpose, content.getBytes(StandardCharsets.UTF_8))

  private def arriveOf(cell: Cell, envelope: String, from: String = "kz-1", moveId: UUID = moveOne, user: UserId = userId) =
    cell.service.flatMap(_.arrive(envelope, user, from, moveId)).either

  def spec = suite("UserMoves")(
    suite("begin")(
      test("marks a user this cell holds as leaving, for the cell and the move named") {
        val cell = Cell(Some(one))
        for
          _ <- cell.departures.find.succeedsWith(None)
          _ <- cell.users.find.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- cell.departures.begin.succeedsWith(true)
          service <- cell.service
          result <- service.begin(userId, "kz-2", moveOne).either
        yield assertTrue(result.isRight, cell.departures.begin.calls.map(call => (call._1, call._2, call._3)) == List((userId, "kz-2", moveOne)))
      },
      test("is accepted again for the same cell and move, writes nothing, and does not look at the user") {
        val cell = Cell(Some(one))
        for
          _ <- cell.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Leaving)))
          service <- cell.service
          result <- service.begin(userId, "kz-2", moveOne).either
        yield assertTrue(result.isRight, cell.departures.begin.calls.isEmpty, cell.users.find.calls.isEmpty)
      },
      test("is refused for another cell, and for another move to the same cell, when the user is leaving already") {
        val cell = Cell(Some(one))
        for
          _ <- cell.departures.find.succeedsWith(Some(departure("kz-3", DepartureState.Leaving)))
          otherCell <- cell.service.flatMap(_.begin(userId, "kz-2", moveOne)).either
          _ <- cell.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Leaving)))
          otherMove <- cell.service.flatMap(_.begin(userId, "kz-2", moveTwo)).either
        yield assertTrue(
          otherCell == Left(MoveError.DepartureExists),
          otherMove == Left(MoveError.DepartureExists),
          cell.departures.begin.calls.isEmpty,
        )
      },
      test("is refused for a user this cell does not hold") {
        val cell = Cell(Some(one))
        for
          _ <- cell.departures.find.succeedsWith(None)
          _ <- cell.users.find.succeedsWith(None)
          service <- cell.service
          result <- service.begin(userId, "kz-2", moveOne).either
        yield assertTrue(result == Left(MoveError.UserNotFound), cell.departures.begin.calls.isEmpty)
      },
      test("is refused for a cell that is not a cell id, and for this cell itself") {
        val cell = Cell(Some(one))
        for
          service <- cell.service
          malformed <- service.begin(userId, "KZ 2", moveOne).either
          own <- service.begin(userId, "kz-1", moveOne).either
        yield assertTrue(malformed == Left(MoveError.InvalidCell), own == Left(MoveError.InvalidCell), cell.departures.find.calls.isEmpty)
      },
      test("is refused by an auth that is no cell") {
        val cell = Cell(None)
        for
          service <- cell.service
          result <- service.begin(userId, "kz-2", moveOne).either
        yield assertTrue(result == Left(MoveError.NotACell), cell.departures.begin.calls.isEmpty)
      },
    ),
    suite("exportUser")(
      test(
        "seals the rows of a leaving user for the cell the departure names, with the digest of what it sealed, and that cell opens them as they were",
      ) {
        val first = Cell(Some(one))
        val second = Cell(Some(two))
        for
          _ <- second.moves.write.succeedsWith(Written.Inserted)
          _ <- knowing(second)
          result <- exported(first)
          target <- second.service
          _ <- target.arrive(result.envelope, userId, "kz-1", moveOne)
          written = second.moves.write.calls.head
        yield assertTrue(
          written._1 == documentOne,
          written._2 == "kz-1",
          result.digest == UserMoveDocument.digest(documentOne),
          first.moves.read.calls == List((userId, moveOne)),
        )
      },
      test("is refused for a user who is not leaving, and for one who has moved; nothing is read") {
        val first = Cell(Some(one))
        for
          _ <- first.departures.find.succeedsWith(None)
          none <- first.service.flatMap(_.exportUser(userId)).either
          _ <- first.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Moved)))
          moved <- first.service.flatMap(_.exportUser(userId)).either
        yield assertTrue(none == Left(MoveError.NotLeaving), moved == Left(MoveError.NotLeaving), first.moves.read.calls.isEmpty)
      },
      test("is refused when this cell has no key for the cell the user is going to, and when the user is gone") {
        val first = Cell(Some(one), known = Map("kz-1" -> TestEnvConfig.authKeyPair))
        val other = Cell(Some(one))
        for
          _ <- first.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Leaving)))
          _ <- first.moves.read.succeedsWith(Some(documentOne))
          noKey <- first.service.flatMap(_.exportUser(userId)).either
          _ <- other.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Leaving)))
          _ <- other.moves.read.succeedsWith(None)
          gone <- other.service.flatMap(_.exportUser(userId)).either
        yield assertTrue(noKey == Left(MoveError.NoCellKey), gone == Left(MoveError.UserNotFound))
      },
    ),
    suite("complete")(
      test("completes the departure and records user.moved_out with the cell the user went to, and no deletion") {
        val cell = Cell(Some(one))
        for
          _ <- cell.moves.complete.succeedsWith(Completed.Moved(toCell = "kz-2", tokens = 2, changed = false))
          service <- cell.service
          result <- service.complete(userId, moveOne, "digest").either
          drafts <- cell.audit.recorded
        yield assertTrue(
          result.isRight,
          audited(drafts, AuthAuditKinds.UserMovedOut, AuthAuditDetails.UserMovedOut(cell = "kz-2", changedAfterExport = false)),
          !drafts.exists(_.kind == AuthAuditKinds.AccountDeleted),
          cell.moves.complete.calls == List((userId, moveOne, "digest")),
        )
      },
      test("rows that changed after the export are still moved, and the event and the counter say so") {
        val cell = Cell(Some(one))
        def counted = Metric.counter("auth_moved_out_changed_total").value.map(_.count)
        for
          before <- counted
          _ <- cell.moves.complete.succeedsWith(Completed.Moved(toCell = "kz-2", tokens = 2, changed = true))
          result <- cell.service.flatMap(_.complete(userId, moveOne, "digest")).either
          drafts <- cell.audit.recorded
          after <- counted
        yield assertTrue(
          result.isRight,
          audited(drafts, AuthAuditKinds.UserMovedOut, AuthAuditDetails.UserMovedOut(cell = "kz-2", changedAfterExport = true)),
          after - before == 1.0,
        )
      },
      test("is accepted again, and the event is not recorded twice") {
        val cell = Cell(Some(one))
        for
          _ <- cell.moves.complete.succeedsWith(Completed.AlreadyMoved("kz-2"))
          service <- cell.service
          result <- service.complete(userId, moveOne, "digest").either
          drafts <- cell.audit.recorded
        yield assertTrue(result.isRight, drafts.isEmpty)
      },
      test("is refused for a user who is not leaving, or whose departure is of another move") {
        val cell = Cell(Some(one))
        for
          _ <- cell.moves.complete.succeedsWith(Completed.NotLeaving)
          service <- cell.service
          result <- service.complete(userId, moveTwo, "digest").either
          drafts <- cell.audit.recorded
        yield assertTrue(result == Left(MoveError.NotLeaving), drafts.isEmpty)
      },
    ),
    suite("cancel")(
      test("abandons a leaving departure") {
        val cell = Cell(Some(one))
        for
          _ <- cell.departures.cancel.succeedsWith(true)
          service <- cell.service
          result <- service.cancel(userId).either
        yield assertTrue(result.isRight)
      },
      test("is accepted for a user who is not leaving, and refused for one who has moved") {
        val none = Cell(Some(one))
        val moved = Cell(Some(one))
        for
          _ <- none.departures.cancel.succeedsWith(false)
          _ <- none.departures.find.succeedsWith(None)
          _ <- moved.departures.cancel.succeedsWith(false)
          _ <- moved.departures.find.succeedsWith(Some(departure("kz-2", DepartureState.Moved)))
          accepted <- none.service.flatMap(_.cancel(userId)).either
          refused <- moved.service.flatMap(_.cancel(userId)).either
        yield assertTrue(accepted.isRight, refused == Left(MoveError.UserMoved))
      },
    ),
    suite("arrive")(
      test("records user.moved_in with the cell the envelope came from, once") {
        val first = Cell(Some(one))
        val second = Cell(Some(two))
        for
          _ <- second.moves.write.succeedsWith(Written.Inserted)
          _ <- knowing(second)
          result <- exported(first)
          _ <- arriveOf(second, result.envelope)
          drafts <- second.audit.recorded
        yield assertTrue(audited(drafts, AuthAuditKinds.UserMovedIn, AuthAuditDetails.UserMoved(cell = "kz-1")))
      },
      test("a catch-up for the same move replaces the rows and records no second event") {
        val first = Cell(Some(one))
        val second = Cell(Some(two))
        for
          _ <- second.moves.write.succeedsWith(Written.Replaced)
          _ <- knowing(second)
          result <- exported(first)
          outcome <- arriveOf(second, result.envelope)
          drafts <- second.audit.recorded
        yield assertTrue(outcome.isRight, drafts.isEmpty)
      },
      test("is refused with user_exists when the user, the move or an identifier is held here, and nothing is recorded") {
        val first = Cell(Some(one))
        val second = Cell(Some(two))
        for
          _ <- second.moves.write.succeedsWith(Written.Taken)
          _ <- knowing(second)
          result <- exported(first)
          outcome <- arriveOf(second, result.envelope)
          drafts <- second.audit.recorded
        yield assertTrue(outcome == Left(MoveError.UserExists), drafts.isEmpty)
      },
      test("is refused when the envelope was sealed by another cell than the one named, for another user, or for another move; nothing is written") {
        val first = Cell(Some(one))
        val second = Cell(Some(two))
        for
          _ <- second.moves.write.succeedsWith(Written.Inserted)
          _ <- knowing(second)
          result <- exported(first)
          otherCell <- arriveOf(second, result.envelope, from = "kz-3")
          otherUser <- arriveOf(second, result.envelope, user = UserId(userTwo))
          otherMove <- arriveOf(second, result.envelope, moveId = moveTwo)
        yield assertTrue(
          otherCell == Left(MoveError.EnvelopeOtherCell),
          otherUser == Left(MoveError.EnvelopeOtherUser),
          otherMove == Left(MoveError.EnvelopeOtherMove),
          second.moves.write.calls.isEmpty,
        )
      },
      test("is refused for an envelope sealed for another cell, one of another purpose, one that is not one, and one that expired") {
        val first = Cell(Some(one))
        val second = Cell(Some(two))
        val third = Cell(Some(RoutingKey.CellId.parse("kz-3").toOption.get), known = keys + ("kz-3" -> TestEnvConfig.impostorKeyPair))
        for
          result <- exported(first)
          // sealed for kz-2 and presented to kz-3
          elsewhere <- arriveOf(third, result.envelope)
          // a handoff of kz-1 for kz-2: made by the same primitive, for another use
          handoff <- envelopeOf(documentOne.toJson, CellEnvelope.Purpose.Handoff)
          wrongUse <- arriveOf(second, handoff)
          garbage <- arriveOf(second, "not an envelope")
          _ <- TestClock.adjust(11.minutes)
          late <- arriveOf(second, result.envelope)
        yield assertTrue(
          elsewhere.left.exists(_.isInstanceOf[MoveError.Envelope]),
          wrongUse == Left(MoveError.Envelope(CellEnvelope.Refused.WrongPurpose)),
          garbage == Left(MoveError.Envelope(CellEnvelope.Refused.Malformed)),
          late == Left(MoveError.Envelope(CellEnvelope.Refused.Expired)),
          second.moves.write.calls.isEmpty,
        )
      },
      test("is refused for a document of a version this build does not know, and for one that is not a document, or names two users") {
        val second = Cell(Some(two))
        def arrives(content: String) = envelopeOf(content).flatMap(arriveOf(second, _))
        for
          _ <- knowing(second)
          future <- arrives(documentOne.copy(version = UserMoveDocument.Version + 1).toJson)
          notADocument <- arrives("""{"hello":"world"}""")
          twoUsers <- arrives(documentOne.copy(userId = UUID.randomUUID()).toJson)
        yield assertTrue(
          future == Left(MoveError.Document(UserMoveDocument.DocumentError.UnsupportedVersion(UserMoveDocument.Version + 1))),
          notADocument == Left(MoveError.Document(UserMoveDocument.DocumentError.Invalid)),
          twoUsers == Left(MoveError.Document(UserMoveDocument.DocumentError.Invalid)),
          second.moves.write.calls.isEmpty,
        )
      },
      test("is refused as too large for a document over the cap, before it is parsed") {
        val second = Cell(Some(two))
        for
          big <- envelopeOf("x" * (UserMoves.MaxDocumentBytes + 1))
          outcome <- arriveOf(second, big)
        yield assertTrue(outcome == Left(MoveError.TooLarge), second.moves.write.calls.isEmpty)
      },
      test("is refused when the rows name a tenant, a client or a scope this cell does not know, and when it knows none") {
        val second = Cell(Some(two))
        val unknownClient = Cell(Some(two))
        val unknownTenant = Cell(Some(two))
        val narrow = Cell(Some(two))
        def invalid(what: String) = Left(MoveError.Document(UserMoveDocument.DocumentError.NotInConfiguration(what)))
        val widerToken = documentOne.copy(refreshTokens = documentOne.refreshTokens.map(_.copy(scope = List("openid", "admin"))))
        for
          _ <- second.moves.write.succeedsWith(Written.Inserted)
          _ <- unknownClient.configuration.findByTenant.succeedsWith(Vector(app))
          _ <- unknownClient.configuration.find.succeedsWith(None)
          _ <- unknownTenant.configuration.findByTenant.succeedsWith(Vector.empty)
          _ <- unknownTenant.configuration.find.succeedsWith(Some(app))
          _ <- knowing(narrow)
          client <- exported(Cell(Some(one))).flatMap(e => arriveOf(unknownClient, e.envelope))
          tenant <- exported(Cell(Some(one))).flatMap(e => arriveOf(unknownTenant, e.envelope))
          scope <- exported(Cell(Some(one)), widerToken).flatMap(e => arriveOf(narrow, e.envelope))
          // the control: the same rows with a scope the client allows are taken
          _ <- knowing(second)
          taken <- exported(Cell(Some(one))).flatMap(e => arriveOf(second, e.envelope))
        yield assertTrue(
          client == invalid("consent_client"),
          tenant == invalid("role_tenant"),
          scope == invalid("token_scope"),
          taken.isRight,
          unknownClient.moves.write.calls.isEmpty,
          unknownTenant.moves.write.calls.isEmpty,
          narrow.moves.write.calls.isEmpty,
        )
      },
    ),
    suite("keep")(
      test("keeps the arrival of the move, and refuses with no_arrival when there is none") {
        val cell = Cell(Some(two))
        for
          _ <- cell.moves.keep.succeedsWith(true)
          kept <- cell.service.flatMap(_.keep(userId, moveOne)).either
          _ <- cell.moves.keep.succeedsWith(false)
          none <- cell.service.flatMap(_.keep(userId, moveTwo)).either
        yield assertTrue(
          kept.isRight,
          none == Left(MoveError.NoArrival),
          cell.moves.keep.calls.map(call => (call._1, call._2)) == List((userId, moveOne), (userId, moveTwo)),
        )
      },
    ),
    suite("forgetArrival")(
      test("removes what a pending arrival left, with the event user.arrival_discarded naming the cell it came from") {
        val cell = Cell(Some(two))
        for
          _ <- cell.moves.discard.succeedsWith(Some("kz-1"))
          service <- cell.service
          _ <- service.forgetArrival(userId, moveOne)
          drafts <- cell.audit.recorded
        yield assertTrue(
          cell.moves.discard.calls == List((userId, moveOne)),
          audited(drafts, AuthAuditKinds.UserArrivalDiscarded, AuthAuditDetails.UserMoved(cell = "kz-1")),
        )
      },
      test("does nothing, and records nothing, when no pending arrival of that move is here") {
        val cell = Cell(Some(two))
        for
          _ <- cell.moves.discard.succeedsWith(None)
          service <- cell.service
          _ <- service.forgetArrival(userId, moveTwo)
          drafts <- cell.audit.recorded
        yield assertTrue(drafts.isEmpty)
      },
    ),
  ).provide(SecureRandom.live, SecurityService.live)
