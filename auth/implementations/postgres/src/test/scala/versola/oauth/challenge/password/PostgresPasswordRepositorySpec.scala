package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, Salt, Secret}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

private case class PasswordRepoEnv(repository: PasswordRepository)

object PostgresPasswordRepositorySpec extends PostgresSpec, DatabaseSpecBase[PasswordRepoEnv]:

  val userId1 = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001"))
  val userId2 = UserId(UUID.fromString("a2000000-0000-0000-0000-000000000002"))

  val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

  def pass(n: Int): Secret = Secret(Array.fill(16)(n.toByte))
  def salt(n: Int): Salt   = Salt(Array.fill(16)(n.toByte))

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PasswordRepoEnv(PostgresPasswordRepository(xa))

  override def beforeEach(env: PasswordRepoEnv) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE user_passwords".update.run())
    }.unit

  def testCases(env: PasswordRepoEnv): List[Spec[PasswordRepoEnv & Scope, Any]] =
    List(
      test("create then list returns the record for the correct user") {
        for
          _    <- TestClock.setTime(baseInstant)
          _    <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 3)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 1,
          rows.head.userId == userId1,
          rows.head.createdAt == baseInstant,
        )
      },

      test("list is ordered newest first by created_at DESC, id DESC") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 1)
          _ <- TestClock.adjust(1.second)
          _ <- env.repository.create(userId1, pass(2), salt(2), historySize = 5, numDifferent = 1)
          _ <- TestClock.adjust(1.second)
          _ <- env.repository.create(userId1, pass(3), salt(3), historySize = 5, numDifferent = 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 3,
          rows(0).createdAt == baseInstant.plusSeconds(2),
          rows(1).createdAt == baseInstant.plusSeconds(1),
          rows(2).createdAt == baseInstant,
        )
      },

      test("list uses id DESC as tiebreaker when created_at timestamps are equal") {
        for
          _    <- TestClock.setTime(baseInstant)
          _    <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 1)
          _    <- env.repository.create(userId1, pass(2), salt(2), historySize = 5, numDifferent = 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 2,
          rows(0).id > rows(1).id,
        )
      },

      test("prune keeps exactly historySize rows after insert") {
        for
          _ <- env.repository.create(userId1, pass(1), salt(1), historySize = 3, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(2), salt(2), historySize = 3, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(3), salt(3), historySize = 3, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(4), salt(4), historySize = 3, numDifferent = 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 3)
      },

      test("prune shrinks history when limit is reduced (>= case)") {
        for
          _ <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(2), salt(2), historySize = 5, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(3), salt(3), historySize = 5, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(4), salt(4), historySize = 5, numDifferent = 1)
          _ <- env.repository.create(userId1, pass(5), salt(5), historySize = 2, numDifferent = 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 2)
      },

      test("prune is deterministic: keeps highest id when created_at timestamps are equal") {
        for
          _          <- TestClock.setTime(baseInstant)
          _          <- env.repository.create(userId1, pass(1), salt(1), historySize = 1, numDifferent = 1)
          afterFirst <- env.repository.list(userId1)
          idOfFirst   = afterFirst.head.id
          _          <- env.repository.create(userId1, pass(2), salt(2), historySize = 1, numDifferent = 1)
          remaining  <- env.repository.list(userId1)
        yield assertTrue(
          remaining.size == 1,
          remaining.head.id > idOfFirst,
        )
      },

      test("create returns PasswordReuseError when password is in recent history") {
        for
          _      <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 3)
          _      <- env.repository.create(userId1, pass(2), salt(2), historySize = 5, numDifferent = 3)
          result <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 3).either
        yield assertTrue(result == Left(PasswordReuseError(3)))
      },

      test("historySize = 0 does not delete the newly inserted password") {
        for
          _    <- env.repository.create(userId1, pass(1), salt(1), historySize = 0, numDifferent = 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 1)
      },

      test("histories are isolated between users") {
        for
          _     <- env.repository.create(userId1, pass(1), salt(1), historySize = 1, numDifferent = 1)
          _     <- env.repository.create(userId2, pass(2), salt(2), historySize = 1, numDifferent = 1)
          rows1 <- env.repository.list(userId1)
          rows2 <- env.repository.list(userId2)
        yield assertTrue(rows1.size == 1, rows2.size == 1)
      },
    )
