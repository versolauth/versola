package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.challenge.password.model.PasswordHistoryChanged
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, Salt, Secret}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

trait PasswordRepositorySpec extends DatabaseSpecBase[PasswordRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001"))
  val userId2 = UserId(UUID.fromString("a2000000-0000-0000-0000-000000000002"))

  val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

  def pass(n: Int): Secret = Secret(Array.fill(16)(n.toByte))
  def salt(n: Int): Salt = Salt(Array.fill(16)(n.toByte))

  /** A change as the service makes it: against the newest permanent password it has just read. */
  def createChecked(
      env: PasswordRepositorySpec.Env,
      userId: UserId,
      password: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
  ) =
    for
      permanents <- env.repository.list(userId).map(_.filter(_.expiresAt.isEmpty))
      _ <- env.repository.create(userId, password, salt, historySize, numDifferent, expectedHead = permanents.map(_.id).maxOption)
    yield ()

  def testCases(env: PasswordRepositorySpec.Env): List[Spec[PasswordRepositorySpec.Env & Scope, Any]] =
    List(
      test("create then list returns the record for the correct user") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 3)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 1,
          rows.head.userId == userId1,
          rows.head.createdAt == baseInstant,
          rows.head.expiresAt == None,
        )
      },
      test("list is ordered newest first by created_at DESC, id DESC") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          _ <- TestClock.adjust(1.second)
          _ <- createChecked(env, userId1, pass(2), salt(2), 5, 1)
          _ <- TestClock.adjust(1.second)
          _ <- createChecked(env, userId1, pass(3), salt(3), 5, 1)
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
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          _ <- createChecked(env, userId1, pass(2), salt(2), 5, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 2,
          rows(0).id > rows(1).id,
        )
      },
      test("prune keeps exactly historySize rows after insert") {
        for
          _ <- createChecked(env, userId1, pass(1), salt(1), 3, 1)
          _ <- createChecked(env, userId1, pass(2), salt(2), 3, 1)
          _ <- createChecked(env, userId1, pass(3), salt(3), 3, 1)
          _ <- createChecked(env, userId1, pass(4), salt(4), 3, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 3)
      },
      test("prune removes multiple excess entries when historySize is reduced") {
        for
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          _ <- createChecked(env, userId1, pass(2), salt(2), 5, 1)
          _ <- createChecked(env, userId1, pass(3), salt(3), 5, 1)
          _ <- createChecked(env, userId1, pass(4), salt(4), 2, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 2)
      },
      test("prune is deterministic: keeps highest id when created_at timestamps are equal") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 1, 1)
          afterFirst <- env.repository.list(userId1)
          idOfFirst = afterFirst.head.id
          _ <- createChecked(env, userId1, pass(2), salt(2), 1, 1)
          remaining <- env.repository.list(userId1)
        yield assertTrue(
          remaining.size == 1,
          remaining.head.id > idOfFirst,
        )
      },
      test("histories are isolated between users") {
        for
          _ <- createChecked(env, userId1, pass(1), salt(1), 1, 1)
          _ <- createChecked(env, userId2, pass(2), salt(2), 1, 1)
          rows1 <- env.repository.list(userId1)
          rows2 <- env.repository.list(userId2)
        yield assertTrue(rows1.size == 1, rows2.size == 1)
      },

      // Concurrency: two simultaneous changes that both checked an empty history. Without the lock both
      // insert; with it the second sees the first's row and is refused, to check again.
      test("of two concurrent changes made against the same history only one is stored") {
        for
          results <- ZIO.collectAllPar(List(
            env.repository.create(userId1, pass(1), salt(1), historySize = 1, numDifferent = 1, expectedHead = None).either,
            env.repository.create(userId1, pass(2), salt(2), historySize = 1, numDifferent = 1, expectedHead = None).either,
          ))
          rows <- env.repository.list(userId1)
        yield assertTrue(
          results.count(_.isRight) == 1,
          results.count(_ == Left(PasswordHistoryChanged)) == 1,
          rows.size == 1,
        )
      },
      test("a change made against a history that has since moved on is refused") {
        for
          _ <- env.repository.create(userId1, pass(1), salt(1), historySize = 5, numDifferent = 1, expectedHead = None)
          stale <- env.repository.create(userId1, pass(2), salt(2), historySize = 5, numDifferent = 1, expectedHead = None).either
          rows <- env.repository.list(userId1)
        yield assertTrue(stale == Left(PasswordHistoryChanged), rows.size == 1)
      },
    )

object PasswordRepositorySpec:
  case class Env(repository: PasswordRepository)
