package versola.user.move

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.model.UserId
import versola.user.move.UserMoveDocument.*
import versola.user.move.UserMoveRepository.{Completed, Written}
import versola.util.{Base64, DatabaseSpecBase}
import zio.json.ast.Json
import zio.test.*
import zio.{Chunk, Task}

import java.time.Instant
import java.util.UUID

/** What a user's departure and the rows that travel with them are held to, whichever storage keeps them
  * (versolauth/versola#1051): the two tables of departures, the document read from one cell and written to another, and the
  * completion that leaves nothing of the user behind but what answers `moved`.
  *
  * The rows a repository cannot write (a user with every column filled, an expired token, a session, a tombstone) are stored
  * through [[UserMoveRepositorySpec.Fixtures]]. */
trait UserMoveRepositorySpec extends DatabaseSpecBase[UserMoveRepositorySpec.Env]:
  self: zio.test.ZIOSpec[TransactorZIO] =>

  import UserMoveRepositorySpec.*

  private def sorted(document: UserMoveDocument): UserMoveDocument =
    def key(bytes: Chunk[Byte]) = Base64.urlEncode(bytes.toArray)
    document.copy(
      passwords = document.passwords.sortBy(_.id),
      passkeys = document.passkeys.sortBy(p => key(p.id)),
      roles = document.roles.sortBy(r => (r.tenantId, r.roleId)),
      consents = document.consents.sortBy(_.clientId),
      refreshTokens = document.refreshTokens.sortBy(t => key(t.id)),
    )

  def testCases(env: Env): List[Spec[Env & zio.Scope, Any]] =
    List(departureCases(env), readCases(env), writeCases(env), arrivalCases(env), completeCases(env))

  private def departureCases(env: Env) =
    suite("departures")(
      test("a user is marked leaving once, for one cell; a repeat writes nothing and keeps the first") {
        for
          _ <- TestClock.setTime(now)
          first <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          again <- env.departures.begin(UserId(userOne), "kz-3", moveTwo, now.plusSeconds(5))
          found <- env.departures.find(UserId(userOne))
        yield assertTrue(
          first,
          !again,
          found.map(_.toCell).contains("kz-2"),
          found.map(_.state).contains(DepartureState.Leaving),
          found.map(_.at).contains(now),
        )
      },
      test("a user who is not leaving has no departure") {
        for found <- env.departures.find(UserId(userOne))
        yield assertTrue(found.isEmpty)
      },
      test("a leaving departure is cancelled, and the user has none after") {
        for
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          cancelled <- env.departures.cancel(UserId(userOne))
          again <- env.departures.cancel(UserId(userOne))
          found <- env.departures.find(UserId(userOne))
        yield assertTrue(cancelled, !again, found.isEmpty)
      },
      test("a moved departure is not cancelled") {
        for
          _ <- env.fixtures.seed(documentOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          _ <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          cancelled <- env.departures.cancel(UserId(userOne))
          found <- env.departures.find(UserId(userOne))
        yield assertTrue(!cancelled, found.map(_.state).contains(DepartureState.Moved))
      },
      test("a moved user's token is found while it can be used, and is not found past its expiry or for another token") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.tombstone(tokenId(1), "kz-2", "app", now.plusSeconds(60))
          found <- env.departures.findToken(mac(1), appClient)
          other <- env.departures.findToken(mac(2), appClient)
          _ <- TestClock.setTime(now.plusSeconds(61))
          late <- env.departures.findToken(mac(1), appClient)
        yield assertTrue(found.contains("kz-2"), other.isEmpty, late.isEmpty)
      },
      test("a tombstone is found only for the client the token was issued to") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.tombstone(tokenId(1), "kz-2", "app", now.plusSeconds(60))
          own <- env.departures.findToken(mac(1), appClient)
          other <- env.departures.findToken(mac(1), otherClient)
        yield assertTrue(own.contains("kz-2"), other.isEmpty)
      },
      test("the cleanup removes the tombstones that have expired and only those") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.tombstone(tokenId(1), "kz-2", "app", now.minusSeconds(1))
          _ <- env.fixtures.tombstone(tokenId(2), "kz-2", "app", now)
          _ <- env.fixtures.tombstone(tokenId(3), "kz-2", "app", now.plusSeconds(1))
          removed <- env.departures.removeExpiredTokens
          left <- env.fixtures.tombstones
        yield assertTrue(removed == 2, left.map(_._1) == List(tokenId(3)))
      },
      test("the summary counts leaving and moved users and the live tombstones, with the latest expiry") {
        for
          _ <- TestClock.setTime(now)
          empty <- env.departures.summary
          _ <- env.fixtures.seed(documentOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          _ <- env.departures.begin(UserId(userTwo), "kz-2", moveOne, now)
          _ <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          _ <- env.fixtures.tombstone(tokenId(9), "kz-2", "app", now.minusSeconds(5))
          summary <- env.departures.summary
        yield assertTrue(
          empty == DepartureSummary(leaving = 0, moved = 0, tokens = 0, tokensUntil = None),
          summary == DepartureSummary(leaving = 1, moved = 1, tokens = 2, tokensUntil = Some(tokenExpiry)),
        )
      },
    )

  private def readCases(env: Env) =
    suite("read")(
      test("a user this cell does not hold has no document") {
        for document <- env.moves.read(UserId(userOne), moveOne)
        yield assertTrue(document.isEmpty)
      },
      test(
        "the document holds every row of the user: two passwords, two passkeys, roles of two tenants, a consent, and the tokens that have not expired",
      ) {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.fixtures.seed(documentTwo)
          document <- env.moves.read(UserId(userOne), moveOne)
        yield assertTrue(
          document.map(sorted) == Some(sorted(documentOne.copy(refreshTokens = documentOne.refreshTokens.filter(_.id != expiredToken.id)))),
          document.exists(_.passwords.size == 2),
          document.exists(_.passkeys.size == 2),
          document.exists(_.roles.map(_.tenantId).toSet.size == 2),
          document.exists(_.refreshTokens.size == 2),
        )
      },
      test("a token rotated away is carried, as a replay of it must still find its family") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          document <- env.moves.read(UserId(userOne), moveOne)
        yield assertTrue(document.exists(_.refreshTokens.exists(t => t.rotatedAt.isDefined && t.idempotencyKey.isDefined)))
      },
      test("nothing of another user is in the document") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.fixtures.seed(documentTwo)
          document <- env.moves.read(UserId(userTwo), moveOne)
        yield assertTrue(document.map(sorted) == Some(sorted(documentTwo)))
      },
    )

  private def writeCases(env: Env) =
    val live = documentOne.copy(refreshTokens = documentOne.refreshTokens.filter(_.id != expiredToken.id))
    suite("write")(
      test("the rows written in an empty cell are the rows that were read, row for row, and the arrival is pending") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          exported <- env.moves.read(UserId(userOne), moveOne).someOrFail("no document")
          // the cell the user is moved to holds nothing of them
          _ <- env.fixtures.seedArrival(userOne, moveOne, "kz-1", "pending", now)
          _ <- env.moves.discard(UserId(userOne), moveOne)
          gone <- env.fixtures.rowsOf(userOne)
          written <- env.moves.write(exported, "kz-1", now)
          imported <- env.moves.read(UserId(userOne), moveOne).someOrFail("no document")
          arrival <- env.fixtures.arrival(userOne)
        yield assertTrue(
          gone == 0,
          written == Written.Inserted,
          imported == exported,
          imported.passwords.map(_.id) == exported.passwords.map(_.id),
          arrival == Some((moveOne, "kz-1", "pending")),
        )
      },
      test("the same move again while pending replaces the user's rows with the document's, in one step") {
        for
          _ <- TestClock.setTime(now)
          first <- env.moves.write(live, "kz-1", now)
          changed = live.copy(
            user = live.user.copy(email = Some("alice@new.example.com"), claims = Json.Obj("name" -> Json.Str("Alice B"))),
            passwords = live.passwords.take(1),
            roles = List(Role("tenant-a", "admin")),
            consents = Nil,
            refreshTokens = live.refreshTokens.take(1),
          )
          second <- env.moves.write(changed, "kz-1", now.plusSeconds(5))
          imported <- env.moves.read(UserId(userOne), moveOne).someOrFail("no document")
          arrival <- env.fixtures.arrival(userOne)
        yield assertTrue(
          first == Written.Inserted,
          second == Written.Replaced,
          sorted(imported) == sorted(changed),
          arrival == Some((moveOne, "kz-1", "pending")),
        )
      },
      test("a replace that clashes with another user's identifier writes nothing and leaves the rows that are there") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.moves.write(live, "kz-1", now)
          _ <- env.fixtures.seed(documentTwo)
          before <- env.moves.read(UserId(userOne), moveOne)
          second <- env.moves.write(live.copy(user = live.user.copy(email = documentTwo.user.email)), "kz-1", now)
          after <- env.moves.read(UserId(userOne), moveOne)
        yield assertTrue(second == Written.Taken, before == after)
      },
      test("an arrival that is kept refuses the same move again, and leaves the rows as they were") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.moves.write(live, "kz-1", now)
          kept <- env.moves.keep(UserId(userOne), moveOne, now)
          before <- env.moves.read(UserId(userOne), moveOne)
          again <- env.moves.write(live.copy(roles = Nil), "kz-1", now)
          after <- env.moves.read(UserId(userOne), moveOne)
        yield assertTrue(kept, again == Written.Taken, before == after)
      },
      test("a move id seen is refused even when the user is gone: a replay of an old envelope resurrects nothing") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seedArrival(userOne, moveOne, "kz-1", "kept", now)
          kept <- env.moves.write(live, "kz-1", now)
          _ <- env.fixtures.seedArrival(userOne, moveOne, "kz-1", "pending", now)
          pendingNoUser <- env.moves.write(live, "kz-1", now)
          rows <- env.fixtures.rowsOf(userOne)
        yield assertTrue(kept == Written.Taken, pendingNoUser == Written.Taken, rows == 0)
      },
      test("another move id for a user who is gone is taken, and the arrival is rewritten for it") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seedArrival(userOne, moveTwo, "kz-3", "kept", now.minusSeconds(60))
          written <- env.moves.write(live, "kz-1", now)
          arrival <- env.fixtures.arrival(userOne)
        yield assertTrue(written == Written.Inserted, arrival == Some((moveOne, "kz-1", "pending")))
      },
      test("a user who is here with no pending arrival of this move is not overwritten, whatever the arrival says") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          none <- env.moves.write(documentOne.copy(roles = Nil), "kz-1", now)
          _ <- env.fixtures.seedArrival(userOne, moveTwo, "kz-3", "pending", now)
          other <- env.moves.write(documentOne.copy(roles = Nil), "kz-1", now)
          document <- env.moves.read(UserId(userOne), moveOne)
        yield assertTrue(none == Written.Taken, other == Written.Taken, document.exists(_.roles.size == 2))
      },
      test("an email, phone or login another user holds is a conflict, and nothing is written") {
        for
          _ <- env.fixtures.seed(documentTwo)
          sameEmail <- env.moves.write(documentOne.copy(user = documentOne.user.copy(email = documentTwo.user.email)), "kz-1", now)
          samePhone <- env.moves.write(documentOne.copy(user = documentOne.user.copy(phone = documentTwo.user.phone)), "kz-1", now)
          sameLogin <- env.moves.write(documentOne.copy(user = documentOne.user.copy(login = documentTwo.user.login)), "kz-1", now)
          written <- env.fixtures.rowsOf(userOne)
          arrival <- env.fixtures.arrival(userOne)
        yield assertTrue(sameEmail == Written.Taken, samePhone == Written.Taken, sameLogin == Written.Taken, written == 0, arrival.isEmpty)
      },
      test("a passkey or a token that is another user's is a conflict, and nothing is written") {
        for
          _ <- env.fixtures.seed(documentTwo)
          passkey <-
            env.moves.write(documentOne.copy(passkeys = documentOne.passkeys.map(_.copy(id = documentTwo.passkeys.head.id)).take(1)), "kz-1", now)
          token <- env.moves.write(
            documentOne.copy(refreshTokens = documentOne.refreshTokens.take(1).map(_.copy(id = documentTwo.refreshTokens.head.id))),
            "kz-1",
            now,
          )
          written <- env.fixtures.rowsOf(userOne)
        yield assertTrue(passkey == Written.Taken, token == Written.Taken, written == 0)
      },
      test("password ids another user holds are not taken: the rows get ids of this cell, in the order they had") {
        for
          _ <- env.fixtures.seed(documentTwo.copy(passwords = documentOne.passwords.map(p => p.copy(password = Chunk.fill(32)(1.toByte)))))
          written <- env.moves.write(documentOne, "kz-1", now)
          imported <- env.moves.read(UserId(userOne), moveOne).someOrFail("no document")
        yield assertTrue(
          written == Written.Inserted,
          imported.passwords.map(_.id).distinct.size == 2,
          imported.passwords.map(_.id) == imported.passwords.map(_.id).sorted,
          imported.passwords.map(_.password) == documentOne.passwords.sortBy(_.id).map(_.password),
          imported.passwords.map(_.id) != documentOne.passwords.map(_.id),
        )
      },
      test("a user who moves back to a cell they left is served again there: the departure and the tombstones of their tokens are gone") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          _ <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          moved <- env.departures.find(UserId(userOne))
          tokenMoved <- env.departures.findToken(mac(1), appClient)
          written <- env.moves.write(live.copy(moveId = moveTwo), "kz-2", now)
          back <- env.departures.find(UserId(userOne))
          tokenBack <- env.departures.findToken(mac(1), appClient)
        yield assertTrue(moved.isDefined, tokenMoved.contains("kz-2"), written == Written.Inserted, back.isEmpty, tokenBack.isEmpty)
      },
    )

  private def arrivalCases(env: Env) =
    val live = documentOne.copy(refreshTokens = documentOne.refreshTokens.filter(_.id != expiredToken.id))
    suite("keep, discard and the cleanup of arrivals")(
      test("keep makes a pending arrival kept, repeats are accepted, and an arrival of another move or none is refused") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.moves.write(live, "kz-1", now)
          wrongMove <- env.moves.keep(UserId(userOne), moveTwo, now)
          wrongUser <- env.moves.keep(UserId(userTwo), moveOne, now)
          kept <- env.moves.keep(UserId(userOne), moveOne, now.plusSeconds(1))
          again <- env.moves.keep(UserId(userOne), moveOne, now.plusSeconds(2))
          arrival <- env.fixtures.arrival(userOne)
        yield assertTrue(!wrongMove, !wrongUser, kept, again, arrival == Some((moveOne, "kz-1", "kept")))
      },
      test("discard of a pending arrival deletes every row of the user and the arrival, and leaves other users and the revocations alone") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.moves.write(live, "kz-1", now)
          _ <- env.fixtures.seed(documentTwo)
          _ <- env.fixtures.addSession(userOne)
          revocations <- env.fixtures.revocations
          from <- env.moves.discard(UserId(userOne), moveOne)
          again <- env.moves.discard(UserId(userOne), moveOne)
          other <- env.moves.discard(UserId(UUID.randomUUID()), moveOne)
          one <- env.fixtures.rowsOf(userOne)
          two <- env.fixtures.rowsOf(userTwo)
          arrival <- env.fixtures.arrival(userOne)
          revocationsAfter <- env.fixtures.revocations
        yield assertTrue(from.contains("kz-1"), again.isEmpty, other.isEmpty, one == 0, two > 0, arrival.isEmpty, revocations == revocationsAfter)
      },
      test("discard does nothing for a kept arrival, for an arrival of another move, and for a user with no arrival") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.moves.write(live, "kz-1", now)
          wrongMove <- env.moves.discard(UserId(userOne), moveTwo)
          _ <- env.moves.keep(UserId(userOne), moveOne, now)
          kept <- env.moves.discard(UserId(userOne), moveOne)
          _ <- env.fixtures.seed(documentTwo)
          noArrival <- env.moves.discard(UserId(userTwo), moveOne)
          one <- env.fixtures.rowsOf(userOne)
          two <- env.fixtures.rowsOf(userTwo)
          arrival <- env.fixtures.arrival(userOne)
        yield assertTrue(wrongMove.isEmpty, kept.isEmpty, noArrival.isEmpty, one > 0, two > 0, arrival == Some((moveOne, "kz-1", "kept")))
      },
      test("the cleanup removes arrivals older than the cut-off whose user is gone, and no other") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seedArrival(userOne, moveOne, "kz-1", "kept", now.minusSeconds(100))
          _ <- env.fixtures.seedArrival(userTwo, moveOne, "kz-1", "kept", now.minusSeconds(10))
          present = UUID.fromString("0198e5a0-0000-7000-8000-000000000003")
          _ <- env.fixtures.seed(documentOne.copy(
            userId = present,
            user = documentOne.user.copy(id = present, email = None, phone = None, login = None),
            passkeys = Nil,
            refreshTokens = Nil,
          ))
          _ <- env.fixtures.seedArrival(present, moveOne, "kz-1", "kept", now.minusSeconds(100))
          removed <- env.moves.removeStaleArrivals(now.minusSeconds(50))
          gone <- env.fixtures.arrival(userOne)
          young <- env.fixtures.arrival(userTwo)
          here <- env.fixtures.arrival(present)
        yield assertTrue(removed == 1, gone.isEmpty, young.isDefined, here.isDefined)
      },
    )

  private def completeCases(env: Env) =
    suite("complete")(
      test("a leaving user is moved: nothing of them is left, and a tombstone is left for each refresh token that has not expired") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.fixtures.seed(documentTwo)
          _ <- env.fixtures.addSession(userOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          result <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          one <- env.fixtures.rowsOf(userOne)
          two <- env.fixtures.rowsOf(userTwo)
          tombstones <- env.fixtures.tombstones
          tombstoned <- env.fixtures.tombstoneClients
          departure <- env.departures.find(UserId(userOne))
          live <- env.departures.findToken(mac(1), appClient)
          expired <- env.departures.findToken(mac(3), appClient)
        yield assertTrue(
          result == Completed.Moved(toCell = "kz-2", tokens = 2, changed = false),
          one == 0,
          two > 0,
          tombstones.map(_._1).toSet == Set(tokenId(1), tokenId(2)),
          tombstones.forall(_._2 == "kz-2"),
          tombstoned.forall(_._3 == "app"),
          departure.map(_.state).contains(DepartureState.Moved),
          live.contains("kz-2"),
          expired.isEmpty,
        )
      },
      test("what a deletion adds is not done: no revocation is recorded") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          before <- env.fixtures.revocations
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          _ <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          after <- env.fixtures.revocations
        yield assertTrue(before == after)
      },
      test("a user who is not leaving is not moved, and keeps every row") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          before <- env.fixtures.rowsOf(userOne)
          result <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          after <- env.fixtures.rowsOf(userOne)
        yield assertTrue(result == Completed.NotLeaving, before == after)
      },
      test("rows that changed since the digest are still moved, and the completion says so") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          result <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne.copy(roles = Nil)))
          rows <- env.fixtures.rowsOf(userOne)
          departure <- env.departures.find(UserId(userOne))
        yield assertTrue(
          result == Completed.Moved(toCell = "kz-2", tokens = 2, changed = true),
          rows == 0,
          departure.map(_.state).contains(DepartureState.Moved),
        )
      },
      test("the completion of another move than the departure's is not done, and the user keeps every row") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          before <- env.fixtures.rowsOf(userOne)
          result <- env.moves.complete(UserId(userOne), moveTwo, digestOf(liveOne))
          after <- env.fixtures.rowsOf(userOne)
          departure <- env.departures.find(UserId(userOne))
        yield assertTrue(result == Completed.NotLeaving, before == after, departure.map(_.state).contains(DepartureState.Leaving))
      },
      test("a repeat of the completion is accepted and changes nothing") {
        for
          _ <- TestClock.setTime(now)
          _ <- env.fixtures.seed(documentOne)
          _ <- env.departures.begin(UserId(userOne), "kz-2", moveOne, now)
          _ <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          tombstones <- env.fixtures.tombstones
          again <- env.moves.complete(UserId(userOne), moveOne, digestOf(liveOne))
          tombstonesAfter <- env.fixtures.tombstones
        yield assertTrue(again == Completed.AlreadyMoved("kz-2"), tombstones == tombstonesAfter)
      },
    )

object UserMoveRepositorySpec:
  case class Env(moves: UserMoveRepository, departures: UserDepartureRepository, fixtures: Fixtures)

  /** What the repositories do not write: a user with every column given as the document says, a session, a tombstone, and what is
    * left in the tables after a move. The storage's runner supplies it. */
  trait Fixtures:
    /** The rows of the document, exactly as it says, expired tokens included. */
    def seed(document: UserMoveDocument): Task[Unit]

    /** A browser session of the user: one of the rows a move ends. */
    def addSession(userId: UUID): Task[Unit]

    /** How many rows the user has in every table of theirs, a session's included. */
    def rowsOf(userId: UUID): Task[Int]

    /** How many revocations are recorded. */
    def revocations: Task[Int]

    def tombstones: Task[List[(Chunk[Byte], String, Instant)]]

    /** The tombstones as `(id, cell, client)`. */
    def tombstoneClients: Task[List[(Chunk[Byte], String, String)]]

    def tombstone(id: Chunk[Byte], toCell: String, clientId: String, expiresAt: Instant): Task[Unit]

    /** The arrival of the user as stored: the move, the cell it came from and its state. */
    def arrival(userId: UUID): Task[Option[(UUID, String, String)]]

    /** An arrival row as the table stores it, with no rows of the user. */
    def seedArrival(userId: UUID, moveId: UUID, from: String, state: String, at: Instant): Task[Unit]

  val now: Instant = Instant.parse("2026-06-01T00:00:00Z")
  val tokenExpiry: Instant = Instant.parse("2026-12-01T00:00:00Z")

  val moveOne: UUID = UUID.fromString("0198e5a0-0000-7000-8000-0000000000b1")
  val moveTwo: UUID = UUID.fromString("0198e5a0-0000-7000-8000-0000000000b2")
  val appClient = versola.oauth.client.model.ClientId("app")
  val otherClient = versola.oauth.client.model.ClientId("other-app")
  def digestOf(document: UserMoveDocument): String = UserMoveDocument.digest(document)
  def liveOne: UserMoveDocument = documentOne.copy(refreshTokens = documentOne.refreshTokens.filter(_.id != expiredToken.id))

  val userOne: UUID = UUID.fromString("0198e5a0-0000-7000-8000-000000000001")
  val userTwo: UUID = UUID.fromString("0198e5a0-0000-7000-8000-000000000002")

  def tokenId(n: Int): Chunk[Byte] = Chunk.fill(32)(n.toByte)
  def mac(n: Int) = versola.util.MAC(tokenId(n).toArray)

  private def bytes(n: Int, length: Int = 16): Chunk[Byte] = Chunk.fill(length)(n.toByte)

  private def token(
      n: Int,
      family: String,
      user: UUID,
      expiresAt: Instant,
      rotated: Boolean,
      cnf: Option[Json],
  ): RefreshToken =
    RefreshToken(
      id = tokenId(n),
      familyId = family,
      rotatedAt = Option.when(rotated)(now.minusSeconds(30)),
      idempotencyKey = Option.when(rotated)(bytes(n + 40)),
      sessionId = bytes(n + 50, 32),
      publicSessionId = s"kz-1.session-$n",
      clientId = "app",
      audience = List("resource://api", "https://api.example.com"),
      scope = List("openid", "offline_access"),
      issuedAt = now.minusSeconds(3600),
      expiresAt = expiresAt,
      requestedClaims = Option.when(n == 2)(Json.Obj("userinfo" -> Json.Obj("email" -> Json.Null))),
      uiLocales = Option.when(n == 1)(List("ru")),
      nonce = Option.when(n == 2)("nonce-2"),
      acr = Some("urn:mace:incommon:iap:silver"),
      amr = Json.Arr(Json.Str("pwd")),
      authTime = now.minusSeconds(3700),
      cnf = cnf,
      authorizationDetails = Option.when(n == 2)(List(Json.Obj("type" -> Json.Str("payment_initiation")))),
    )

  val expiredToken: RefreshToken = token(3, "family-b", userOne, now.minusSeconds(86400), rotated = false, cnf = None)

  /** A user with every kind of row, in every shape a column takes: two passwords, one of them temporary, two passkeys, one with
    * every optional column and one with none, roles in two tenants, a consent, and three refresh tokens: one rotated away, one
    * bound to a key, one expired. */
  val documentOne: UserMoveDocument =
    UserMoveDocument(
      version = UserMoveDocument.Version,
      userId = userOne,
      moveId = moveOne,
      user = User(
        id = userOne,
        email = Some("alice@example.com"),
        phone = Some("+77001112233"),
        login = Some("alice"),
        claims = Json.Obj("name" -> Json.Str("Alice"), "email_verified" -> Json.Bool(true), "age" -> Json.Num(31)),
        uiLocales = Some(List("ru", "en")),
        lastVersion = Some(UUID.fromString("0198e5a0-0000-7000-8000-0000000000aa")),
      ),
      passwords = List(
        Password(id = 7, password = bytes(1, 32), salt = bytes(2), createdAt = now.minusSeconds(86400), expiresAt = None),
        Password(id = 9, password = bytes(3, 32), salt = bytes(4), createdAt = now.minusSeconds(60), expiresAt = Some(now.plusSeconds(3600))),
      ),
      passkeys = List(
        Passkey(
          id = bytes(11, 32),
          publicKey = bytes(12, 64),
          signatureCounter = 42L,
          deviceType = "MultiDevice",
          backedUp = true,
          backupEligible = true,
          transports = Some(List("internal", "hybrid")),
          attestationObject = Some(bytes(13, 24)),
          clientDataJson = Some(bytes(14, 24)),
          aaguid = Some(bytes(15)),
          name = Some("Alice's phone"),
          lastUsedAt = Some(now.minusSeconds(10)),
          createdAt = now.minusSeconds(100000),
          updatedAt = now.minusSeconds(10),
        ),
        Passkey(
          id = bytes(21, 32),
          publicKey = bytes(22, 64),
          signatureCounter = 0L,
          deviceType = "SingleDevice",
          backedUp = false,
          backupEligible = false,
          transports = None,
          attestationObject = None,
          clientDataJson = None,
          aaguid = None,
          name = None,
          lastUsedAt = None,
          createdAt = now.minusSeconds(200000),
          updatedAt = now.minusSeconds(200000),
        ),
      ),
      roles = List(Role("tenant-a", "admin"), Role("tenant-b", "viewer")),
      consents = List(Consent(clientId = "app", scope = List("openid", "profile"), grantedAt = now.minusSeconds(5000), expiresAt = None)),
      refreshTokens = List(
        token(1, "family-a", userOne, tokenExpiry, rotated = true, cnf = None),
        token(2, "family-a", userOne, tokenExpiry, rotated = false, cnf = Some(Json.Obj("jkt" -> Json.Str("thumbprint")))),
        expiredToken,
      ),
    )

  /** Another user, who shares nothing with the first; has a passkey and a token of her own. */
  val documentTwo: UserMoveDocument =
    UserMoveDocument(
      version = UserMoveDocument.Version,
      userId = userTwo,
      moveId = moveOne,
      user = User(
        id = userTwo,
        email = Some("bob@example.com"),
        phone = Some("+77004445566"),
        login = Some("bob"),
        claims = Json.Obj(),
        uiLocales = None,
        lastVersion = None,
      ),
      passwords = List(Password(id = 8, password = bytes(5, 32), salt = bytes(6), createdAt = now.minusSeconds(7), expiresAt = None)),
      passkeys = List(documentOne.passkeys.head.copy(id = bytes(31, 32))),
      roles = List(Role("tenant-a", "viewer")),
      consents = Nil,
      refreshTokens = List(token(5, "family-c", userTwo, tokenExpiry, rotated = false, cnf = None)),
    )
