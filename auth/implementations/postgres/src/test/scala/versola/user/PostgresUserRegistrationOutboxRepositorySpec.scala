package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import versola.user.model.UserId
import versola.util.postgres.PostgresSpec
import versola.util.{DatabaseSpecBase, Email}
import zio.test.*
import zio.{Clock, Scope, ZIO, ZLayer, durationInt}

import java.util.UUID

case class RegistrationOutboxEnv(
    userRepository: UserRepository,
    outboxRepository: UserRegistrationOutboxRepository,
)

object PostgresUserRegistrationOutboxRepositorySpec
    extends PostgresSpec,
      DatabaseSpecBase[RegistrationOutboxEnv]:

  private val userId = UserId(UUID.fromString("3f6c0f9e-9a7e-4f3e-8f2a-1d2c3b4a5e6f"))
  private val email = Email("registered@example.com")

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield RegistrationOutboxEnv(PostgresUserRepository(xa), PostgresUserRegistrationOutboxRepository(xa))

  override def beforeEach(env: RegistrationOutboxEnv) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
             sql"TRUNCATE TABLE users, user_registration_outbox, user_registration_outbox_dead CASCADE".update.run()
    yield ()

  private def register(env: RegistrationOutboxEnv) =
    for
      now <- Clock.instant
      result <- env.userRepository.findOrCreateForRegistration(userId, Left(email), UUID.randomUUID(), now)
    yield result

  override def testCases(env: RegistrationOutboxEnv): List[zio.test.Spec[RegistrationOutboxEnv & Scope, Any]] =
    List(
      test("a registration is queued with the account and claimable for dispatch") {
        for
          (user, wasCreated) <- register(env)
          claimed <- env.outboxRepository.claimDueEvents(10, 30.seconds)
        yield assertTrue(
          wasCreated,
          claimed.size == 1,
          claimed.head.userId == user.id,
          claimed.head.event == UserRegisteredEvent(user.id, Some(email), None, None),
        )
      },
      test("an account that already existed queues nothing") {
        for
          _ <- register(env)
          _ <- env.outboxRepository.claimDueEvents(10, 30.seconds).flatMap: claimed =>
                 ZIO.foreachDiscard(claimed)(record => env.outboxRepository.deleteEvent(record.id))
          now <- Clock.instant
          (_, wasCreated) <- env.userRepository
                               .findOrCreateForRegistration(UserId(UUID.randomUUID()), Left(email), UUID.randomUUID(), now)
          claimed <- env.outboxRepository.claimDueEvents(10, 30.seconds)
        yield assertTrue(
          !wasCreated,
          claimed.isEmpty,
        )
      },
      test("a claimed event is leased so a concurrent processor skips it") {
        for
          _ <- register(env)
          first <- env.outboxRepository.claimDueEvents(10, 30.seconds)
          second <- env.outboxRepository.claimDueEvents(10, 30.seconds)
        yield assertTrue(
          first.size == 1,
          second.isEmpty,
        )
      },
      test("a dispatched event is removed from the queue") {
        for
          _ <- register(env)
          claimed <- env.outboxRepository.claimDueEvents(10, 30.seconds)
          _ <- env.outboxRepository.deleteEvent(claimed.head.id)
          remaining <- env.outboxRepository.claimDueEvents(10, 0.seconds)
        yield assertTrue(remaining.isEmpty)
      },
      test("a rescheduled event becomes claimable again and counts the attempt") {
        for
          _ <- register(env)
          claimed <- env.outboxRepository.claimDueEvents(10, 30.seconds)
          _ <- env.outboxRepository.rescheduleEvent(claimed.head.id, 0.seconds)
          retried <- env.outboxRepository.claimDueEvents(10, 30.seconds)
        yield assertTrue(
          retried.size == 1,
          retried.head.attempts == claimed.head.attempts + 1,
        )
      },
      test("an exhausted event moves to the dead letter table and stops being claimed") {
        for
          _ <- register(env)
          claimed <- env.outboxRepository.claimDueEvents(10, 30.seconds)
          _ <- env.outboxRepository.moveToDeadLetter(claimed.head.id, "central unreachable")
          remaining <- env.outboxRepository.claimDueEvents(10, 0.seconds)
        yield assertTrue(remaining.isEmpty)
      },
    )
