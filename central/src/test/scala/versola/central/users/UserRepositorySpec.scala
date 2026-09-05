package versola.central.users

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{DatabaseSpecBase, Email, Patch, Phone, SecureRandom}
import zio.test.*
import zio.{Duration, ZIO, ZLayer}

import java.util.UUID

trait UserRepositorySpec extends DatabaseSpecBase[UserRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val userId1 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val userId2 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val email = Email("user@example.com")
  private val phone = Phone("+15551234567")
  private val login = Login("nickname")

  /** Claims and acknowledges everything currently queued, so a later assertion sees only the
    * events the test itself provoked. The outbox is per-user FIFO, so simply claiming the
    * earlier events would leave them leased and block the ones under test.
    */
  private def drainOutbox(env: UserRepositorySpec.Env) =
    env.repository.claimDueEvents(100, Duration.fromSeconds(0))
      .flatMap(ZIO.foreachDiscard(_)(record => env.repository.deleteEvent(record.id)))

  override def testCases(env: UserRepositorySpec.Env) =
    List(
      test("create inserts user and is retrievable by email/phone/login") {
        for
          _ <- env.repository.create(userId1, Some(email), Some(phone), Some(login))
          byEmail <- env.repository.findByEmail(email)
          byPhone <- env.repository.findByPhone(phone)
          byLogin <- env.repository.findByLogin(login)
        yield assertTrue(
          byEmail.contains(UserIndexRecord(userId1, Some(email), Some(phone), Some(login))),
          byPhone.contains(UserIndexRecord(userId1, Some(email), Some(phone), Some(login))),
          byLogin.contains(UserIndexRecord(userId1, Some(email), Some(phone), Some(login))),
        )
      },
      test("create fails with UserConflict when email already exists for another user") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          result <- env.repository.create(userId2, Some(email), None, None).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("create fails with UserConflict when phone already exists for another user") {
        for
          _ <- env.repository.create(userId1, None, Some(phone), None)
          result <- env.repository.create(userId2, None, Some(phone), None).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("create fails with UserConflict when login already exists for another user") {
        for
          _ <- env.repository.create(userId1, None, None, Some(login))
          result <- env.repository.create(userId2, None, None, Some(login)).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("create with the same id is treated as an upsert and does not conflict") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          _ <- env.repository.create(userId1, Some(email), Some(phone), None)
          found <- env.repository.findById(userId1)
        yield assertTrue(found.contains(UserIndexRecord(userId1, Some(email), Some(phone), None)))
      },
      test("registration claim mints a new id and queues a recovery upsert") {
        for
          owner <- env.repository.indexFromAuth(Some(email), None, None)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
          upsertUserIds = events.map(_.event).collect { case event: OutboxEvent.UpsertUser => event.userId }
        yield assertTrue(upsertUserIds == Vector(owner))
      },
      test("registration claim returns the existing credential owner") {
        for
          first <- env.repository.indexFromAuth(Some(email), None, None)
          second <- env.repository.indexFromAuth(Some(email), None, None)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(
          first == second,
          events.size == 1,
        )
      },
      test("concurrent registration claims converge on one user id") {
        for
          owners <- ZIO.collectAllPar(
            List(
              env.repository.indexFromAuth(Some(email), None, None),
              env.repository.indexFromAuth(Some(email), None, None),
            ),
          )
          indexed <- env.repository.findByEmail(email)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
        yield assertTrue(
          owners.toSet.size == 1,
          indexed.map(_.id).contains(owners.head),
          events.size == 1,
        )
      },
      test("registration claim fails with UserIndexConflict when credentials resolve to different users") {
        for
          _ <- env.repository.indexFromAuth(Some(email), None, None)
          _ <- env.repository.indexFromAuth(None, Some(phone), None)
          result <- env.repository.indexFromAuth(Some(email), Some(phone), None).either
        yield assertTrue(result == Left(UserIndexConflict))
      },
      test("patch applies the three-state semantics per column") {
        for
          _ <- env.repository.create(userId1, Some(email), Some(phone), Some(login))
          _ <- env.repository.patch(
            userId1,
            email = Some(Patch.Modified(Email("new@example.com"))),
            phone = Some(Patch.Deleted),
            login = None,
          )
          found <- env.repository.findById(userId1)
        yield assertTrue(
          found == Some(UserIndexRecord(userId1, Some(Email("new@example.com")), None, Some(login))),
        )
      },
      test("patch queues an upsert carrying the full post-patch state") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          _ <- drainOutbox(env)
          _ <- env.repository.patch(userId1, Some(Patch.Modified(Email("new@example.com"))), None, None)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
          upserts = events.map(_.event).collect { case e: OutboxEvent.UpsertUser => e }
        yield assertTrue(
          upserts.map(_.userId) == Vector(userId1),
          upserts.map(_.email) == Vector(Some(Email("new@example.com"))),
        )
      },
      test("enqueueRoleUpdate queues the role change for auth") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
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
      test("delete removes the index entry and queues a delete for auth") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          _ <- drainOutbox(env)
          _ <- env.repository.delete(userId1)
          found <- env.repository.findById(userId1)
          events <- env.repository.claimDueEvents(10, Duration.fromSeconds(60))
          deletes = events.map(_.event).collect { case e: OutboxEvent.DeleteUser => e }
        yield assertTrue(found.isEmpty, deletes.map(_.userId) == Vector(userId1))
      },
      test("deleteEvent drops a dispatched event from the outbox") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          claimed <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- ZIO.foreachDiscard(claimed)(record => env.repository.deleteEvent(record.id))
          remaining <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(claimed.nonEmpty, remaining.isEmpty)
      },
      test("rescheduleEvent counts the attempt and holds the event back") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          claimed <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- ZIO.foreachDiscard(claimed)(record => env.repository.rescheduleEvent(record.id, Duration.fromSeconds(60)))
          heldBack <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(claimed.map(_.attempts) == Vector(0), heldBack.isEmpty)
      },
      test("moveToDeadLetter takes the event out of the outbox for good") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          claimed <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- ZIO.foreachDiscard(claimed)(record => env.repository.moveToDeadLetter(record.id, "auth rejected it"))
          remaining <- env.repository.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(claimed.nonEmpty, remaining.isEmpty)
      },
    )

object UserRepositorySpec:
  case class Env(repository: UserRepository)

  val testLayer: ZLayer[UserRepository, Nothing, Env] =
    ZLayer.fromFunction(Env(_))
