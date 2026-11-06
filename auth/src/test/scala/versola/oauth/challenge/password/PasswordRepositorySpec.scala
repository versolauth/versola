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

      // First-login token of the bootstrap admin (issue #824).
      test("a bootstrap credential is created once; a second attempt finds a usable one and changes nothing") {
        for
          _ <- TestClock.setTime(baseInstant)
          first <- bootstrap(env, userId1, token(1))
          second <- bootstrap(env, userId1, token(2))
          rows <- env.repository.list(userId1)
          viaFirst <- consume(env, token(1))
          viaSecond <- consume(env, token(2))
        yield assertTrue(first, !second, rows.size == 1, viaFirst == Some(userId1), viaSecond.isEmpty)
      },
      test("of several replicas bootstrapping at once exactly one issues the credential and the link") {
        for
          _ <- TestClock.setTime(baseInstant)
          results <- ZIO.collectAllPar((1 to 6).toList.map(n => bootstrap(env, userId1, token(n))))
          rows <- env.repository.list(userId1)
        yield assertTrue(results.count(identity) == 1, rows.size == 1)
      },
      test("a token is spent by its first use only") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          first <- consume(env, token(1))
          again <- consume(env, token(1))
        yield assertTrue(first == Some(userId1), again.isEmpty)
      },
      test("two concurrent uses of one token: only one wins") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          results <- ZIO.collectAllPar(List.fill(4)(consume(env, token(1))))
        yield assertTrue(results.count(_.isDefined) == 1)
      },
      test("an unknown token spends nothing") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          unknown <- consume(env, token(9))
          stillThere <- consume(env, token(1))
        yield assertTrue(unknown.isEmpty, stillThere == Some(userId1))
      },
      test("an expired token is refused") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- TestClock.adjust(25.hours)
          expired <- consume(env, token(1))
        yield assertTrue(expired.isEmpty)
      },
      test("an expired temporary password is replaced, together with its token, on the next bootstrap") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- TestClock.adjust(25.hours)
          again <- bootstrap(env, userId1, token(2))
          rows <- env.repository.list(userId1)
          old <- consume(env, token(1))
          fresh <- consume(env, token(2))
        yield assertTrue(again, rows.size == 1, old.isEmpty, fresh == Some(userId1))
      },
      test("setting a permanent password kills the token") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          afterSet <- consume(env, token(1))
        yield assertTrue(afterSet.isEmpty)
      },
      test("a bootstrap is not issued for a user who has a permanent password") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          created <- bootstrap(env, userId1, token(1))
          viaToken <- consume(env, token(1))
        yield assertTrue(!created, viaToken.isEmpty)
      },
      test("reset drops the unused temporary credential and its token; the next bootstrap issues new ones") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          reset <- env.repository.resetBootstrapCredential(userId1)
          rowsAfterReset <- env.repository.list(userId1)
          oldToken <- consume(env, token(1))
          again <- bootstrap(env, userId1, token(2))
          newToken <- consume(env, token(2))
        yield assertTrue(reset, rowsAfterReset.isEmpty, oldToken.isEmpty, again, newToken == Some(userId1))
      },
      test("reset is refused once a permanent password exists and deletes nothing") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          reset <- env.repository.resetBootstrapCredential(userId1)
          rows <- env.repository.list(userId1)
        yield assertTrue(!reset, rows.size == 1, rows.head.expiresAt.isEmpty)
      },
      test("replacing or deleting the temporary password revokes the first-login token issued with it") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- env.repository.createTemporary(userId1, pass(2), salt(2), baseInstant.plusSeconds(3600))
          afterReplace <- consume(env, token(1))
          _ <- bootstrap(env, userId2, token(2))
          _ <- env.repository.deleteTemporary(userId2)
          afterDelete <- consume(env, token(2))
        yield assertTrue(afterReplace.isEmpty, afterDelete.isEmpty)
      },
      test("a spent link says what it was for") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          spent <- env.repository.consumeLink(token(1))
        yield assertTrue(spent == Some(ConsumedLink(userId1, OneTimeLinkPurpose.FirstLogin)))
      },
      test("a token is bound to its user: another user's reset leaves it alone") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- env.repository.resetBootstrapCredential(userId2)
          stillThere <- consume(env, token(1))
        yield assertTrue(stillThere == Some(userId1))
      },
    )

  def token(n: Int): Array[Byte] = Array.fill(32)(n.toByte)

  /** Whose link was spent, leaving out what it was for (the purpose has its own test). */
  def consume(env: PasswordRepositorySpec.Env, tokenHash: Array[Byte]) =
    env.repository.consumeLink(tokenHash).map(_.map(_.userId))

  /** What auth's bootstrap does on start: a temporary password good for 24 hours and a token valid as long. */
  def bootstrap(env: PasswordRepositorySpec.Env, userId: UserId, tokenHash: Array[Byte]) =
    Clock.instant.flatMap: now =>
      env.repository.createBootstrapCredential(
        userId = userId,
        passwordHash = pass(7),
        salt = salt(7),
        expiresAt = now.plusSeconds(24 * 3600),
        linkTokenHash = Some(tokenHash),
      )

object PasswordRepositorySpec:
  case class Env(repository: PasswordRepository)
