package versola.central.users

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.CellId
import versola.util.{DatabaseSpecBase, Email, Patch, Phone, Secret}
import zio.test.*
import zio.{Duration, ZIO}

import java.util.UUID

/** The directory (versolauth/versola#1041) and the outbox to `auth`, held to by every storage. */
trait UserRepositorySpec extends DatabaseSpecBase[UserRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val userId1 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val userId2 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val userId3 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
  private val key = DirectoryKey.derive(Secret(Array.fill(16)(7.toByte)))
  private val email = key.email(Email("user@example.com"))
  private val phone = key.phone(Phone("+15551234567"))
  private val otherPhone = key.phone(Phone("+15557654321"))
  private val login = key.login(Login("nickname"))
  private val cell = CellId("kz-1")
  private val otherCell = CellId("kz-2")

  private def user(id: UserId, cellId: Option[CellId] = None) = UserDirectoryRecord(id, cellId)

  private def eventId(n: Int) = UUID.fromString(f"00000000-0000-7000-8000-$n%012d")

  private def upsert(id: UserId, n: Int) = OutboxEvent.UpsertUser(userId = id, version = eventId(n), email = None, phone = None, login = None)

  /** The event of a creation has an id of its own for each user, apart from the ids the cases use for later events. */
  private def created(id: UserId) = 100 + id.toString.last.asDigit

  private def create(env: UserRepositorySpec.Env, id: UserId, identifiers: DirectoryIdentifier*) =
    env.repository.create(user = user(id), identifiers = identifiers.toList, event = upsert(id, created(id)), eventId = eventId(created(id)))

  /** Claims and acknowledges everything currently queued, so a later assertion sees only the
    * events the test itself provoked. The outbox is per-user FIFO, so simply claiming the
    * earlier events would leave them leased and block the ones under test.
    */
  private def drainOutbox(env: UserRepositorySpec.Env) =
    env.repository.claimDueEvents(100, Duration.fromSeconds(0))
      .flatMap(ZIO.foreachDiscard(_)(record => env.repository.deleteEvent(record.id)))

  override def testCases(env: UserRepositorySpec.Env) =
    List(
      test("a created user is found by its id and by each of its identifiers, and its creation is queued for auth") {
        for
          _ <- create(env, userId1, email, phone, login)
          byId <- env.repository.find(userId1)
          byEmail <- env.repository.findByIdentifier(email)
          byPhone <- env.repository.findByIdentifier(phone)
          byLogin <- env.repository.findByIdentifier(login)
          kinds <- env.repository.identifierKinds(userId1)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(
          List(byId, byEmail, byPhone, byLogin).forall(_.contains(user(userId1))),
          kinds == Set(IdentifierKind.Email, IdentifierKind.Phone, IdentifierKind.Login),
          events.map(_.event) == Vector(upsert(userId1, created(userId1))),
        )
      },
      test("an identifier nobody holds, or a value of another kind, finds nobody") {
        for
          _ <- create(env, userId1, phone)
          unknown <- env.repository.findByIdentifier(otherPhone)
          // The same bytes under another kind are another row: the kind is part of the key.
          wrongKind <- env.repository.findByIdentifier(phone.copy(kind = IdentifierKind.Email))
        yield assertTrue(unknown.isEmpty, wrongKind.isEmpty)
      },
      test("create fails with UserConflict when an identifier belongs to another user, and stores nothing of the second") {
        for
          _ <- create(env, userId1, phone)
          _ <- drainOutbox(env)
          result <- create(env, userId2, email, phone).either
          second <- env.repository.find(userId2)
          byEmail <- env.repository.findByIdentifier(email)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(result == Left(UserConflict), second.isEmpty, byEmail.isEmpty, events.isEmpty)
      },
      test("create fails with UserConflict when the id is taken") {
        for
          _ <- create(env, userId1, phone)
          result <- create(env, userId1, email).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("a claim of identifiers nobody holds gives them to the candidate, in the candidate's cell, and queues nothing") {
        for
          owner <- env.repository.claim(List(phone), user(userId1, Some(cell)))
          found <- env.repository.findByIdentifier(phone)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(owner == user(userId1, Some(cell)), found.contains(user(userId1, Some(cell))), events.isEmpty)
      },
      test("a repeated claim answers the first owner and its cell, whoever the candidate and whatever its cell") {
        for
          first <- env.repository.claim(List(phone), user(userId1, Some(cell)))
          second <- env.repository.claim(List(phone), user(userId2, Some(otherCell)))
          candidate <- env.repository.find(userId2)
        yield assertTrue(first == user(userId1, Some(cell)), second == first, candidate.isEmpty)
      },
      test("concurrent claims of one identifier converge on one user") {
        for
          owners <- ZIO.foreachPar(1 to 8)(n => env.repository.claim(List(phone), user(UserId(eventId(n)))))
          found <- env.repository.findByIdentifier(phone)
        yield assertTrue(owners.map(_.id).distinct.size == 1, found.map(_.id) == owners.headOption.map(_.id))
      },
      test("a claim fails with UserIndexConflict when its identifiers belong to different users, and claims nothing") {
        for
          _ <- env.repository.claim(List(email), user(userId1))
          _ <- env.repository.claim(List(phone), user(userId2))
          result <- env.repository.claim(List(email, phone), user(userId3)).either
          third <- env.repository.find(userId3)
        yield assertTrue(result == Left(UserIndexConflict), third.isEmpty)
      },
      test("replaceIdentifiers swaps the kinds it names, removes the kinds asked and keeps the rest") {
        for
          _ <- create(env, userId1, email, phone, login)
          _ <- drainOutbox(env)
          event = OutboxEvent.PatchUser(userId = userId1, version = eventId(2), email = Some(Patch.Deleted), phone = None, login = None)
          _ <- env.repository.replaceIdentifiers(
            id = userId1,
            set = List(otherPhone),
            remove = Set(IdentifierKind.Email),
            event = event,
            eventId = eventId(2),
          )
          oldPhone <- env.repository.findByIdentifier(phone)
          newPhone <- env.repository.findByIdentifier(otherPhone)
          byEmail <- env.repository.findByIdentifier(email)
          byLogin <- env.repository.findByIdentifier(login)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(
          oldPhone.isEmpty,
          newPhone.contains(user(userId1)),
          byEmail.isEmpty,
          byLogin.contains(user(userId1)),
          events.map(_.event) == Vector(event),
        )
      },
      test("an identifier a user gave up can be claimed by another") {
        for
          _ <- create(env, userId1, phone)
          _ <- env.repository.replaceIdentifiers(
            id = userId1,
            set = Nil,
            remove = Set(IdentifierKind.Phone),
            event = upsert(userId1, 2),
            eventId = eventId(2),
          )
          owner <- env.repository.claim(List(phone), user(userId2))
        yield assertTrue(owner == user(userId2))
      },
      test("replaceIdentifiers fails with UserNotFound for an unknown user and queues nothing") {
        for
          result <- env.repository.replaceIdentifiers(
            id = userId1,
            set = List(phone),
            remove = Set.empty,
            event = upsert(userId1, 2),
            eventId = eventId(2),
          ).either
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(result == Left(UserNotFound), events.isEmpty)
      },
      test("replaceIdentifiers fails with UserConflict when the identifier belongs to another user, and changes nothing") {
        for
          _ <- create(env, userId1, phone)
          _ <- create(env, userId2, otherPhone, email)
          _ <- drainOutbox(env)
          result <- env.repository.replaceIdentifiers(
            id = userId2,
            set = List(phone),
            remove = Set(IdentifierKind.Email),
            event = upsert(userId2, 2),
            eventId = eventId(2),
          ).either
          owner <- env.repository.findByIdentifier(phone)
          kept <- env.repository.findByIdentifier(otherPhone)
          keptEmail <- env.repository.findByIdentifier(email)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(
          result == Left(UserConflict),
          owner.contains(user(userId1)),
          kept.contains(user(userId2)),
          keptEmail.contains(user(userId2)),
          events.isEmpty,
        )
      },
      test("pendingEvents are the user's undelivered events, oldest first, and no other user's") {
        val patch = OutboxEvent.PatchUser(userId = userId1, version = eventId(200), email = Some(Patch.Deleted), phone = None, login = None)
        for
          _ <- create(env, userId1, email)
          _ <- create(env, userId2, phone)
          _ <- env.repository.replaceIdentifiers(id = userId1, set = Nil, remove = Set(IdentifierKind.Email), event = patch, eventId = eventId(200))
          pending <- env.repository.pendingEvents(userId1)
          // Twice: the outbox hands out one event of a user at a time, the next only once the one before is gone.
          _ <- drainOutbox(env) *> drainOutbox(env)
          delivered <- env.repository.pendingEvents(userId1)
        yield assertTrue(pending == Vector(upsert(userId1, created(userId1)), patch), delivered.isEmpty)
      },
      test("enqueueRoleUpdate queues the role change for auth") {
        for
          _ <- create(env, userId1, email)
          _ <- drainOutbox(env)
          _ <- env.repository.enqueueRoleUpdate(userId1, TenantId("tenant-a"), Set(RoleId("role-a")), Set(RoleId("role-b")))
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
          roleUpdates = events.map(_.event).collect { case e: OutboxEvent.UpdateUserRoles => e }
        yield assertTrue(
          roleUpdates.map(_.userId) == Vector(userId1),
          roleUpdates.map(_.add) == Vector(Set(RoleId("role-a"))),
          roleUpdates.map(_.remove) == Vector(Set(RoleId("role-b"))),
        )
      },
      test("delete removes the user with its identifiers, which can be claimed again, and queues a delete for auth") {
        for
          _ <- create(env, userId1, email, phone)
          _ <- drainOutbox(env)
          _ <- env.repository.delete(userId1)
          found <- env.repository.find(userId1)
          byPhone <- env.repository.findByIdentifier(phone)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
          deletes = events.map(_.event).collect { case e: OutboxEvent.DeleteUser => e }
          owner <- env.repository.claim(List(phone), user(userId2))
        yield assertTrue(found.isEmpty, byPhone.isEmpty, deletes.map(_.userId) == Vector(userId1), owner == user(userId2))
      },
      test("deleteEvent drops a dispatched event from the outbox") {
        for
          _ <- create(env, userId1, email)
          claimed <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- ZIO.foreachDiscard(claimed)(record => env.repository.deleteEvent(record.id))
          remaining <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(claimed.nonEmpty, remaining.isEmpty)
      },
      test("rescheduleEvent counts the attempt and holds the event back") {
        for
          _ <- create(env, userId1, email)
          claimed <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- ZIO.foreachDiscard(claimed)(record => env.repository.rescheduleEvent(record.id, Duration.fromSeconds(60)))
          heldBack <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(claimed.map(_.attempts) == Vector(0), heldBack.isEmpty)
      },
      test("moveToDeadLetter takes the event out of the outbox for good") {
        for
          _ <- create(env, userId1, email)
          claimed <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- ZIO.foreachDiscard(claimed)(record => env.repository.moveToDeadLetter(record.id, "auth rejected it"))
          remaining <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(claimed.nonEmpty, remaining.isEmpty)
      },
    )

object UserRepositorySpec:
  case class Env(repository: UserRepository)
