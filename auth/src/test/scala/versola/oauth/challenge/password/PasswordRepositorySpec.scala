package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
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

  def testCases(env: PasswordRepositorySpec.Env): List[Spec[PasswordRepositorySpec.Env & Scope, Any]] =
    List(
      test("create then list returns the record for the correct user") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- env.repository.create(userId1, pass(1), salt(1))
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 1,
          rows.head.userId == userId1,
          rows.head.createdAt == baseInstant,
          rows.head.expiresAt == None,
        )
      },
      test("create replaces the user's previous password: a user keeps a single one") {
        for
          _ <- env.repository.create(userId1, pass(1), salt(1))
          _ <- env.repository.create(userId1, pass(2), salt(2))
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 1, rows.head.password.sameElements(pass(2)))
      },
      test("create also replaces a temporary password") {
        for
          _ <- env.repository.create(userId1, pass(1), salt(1))
          _ <- env.repository.createTemporary(userId1, pass(2), salt(2), baseInstant.plusSeconds(60))
          _ <- env.repository.create(userId1, pass(3), salt(3))
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 1, rows.head.expiresAt.isEmpty, rows.head.password.sameElements(pass(3)))
      },
      test("passwords are isolated between users") {
        for
          _ <- env.repository.create(userId1, pass(1), salt(1))
          _ <- env.repository.create(userId2, pass(2), salt(2))
          _ <- env.repository.create(userId2, pass(3), salt(3))
          rows1 <- env.repository.list(userId1)
          rows2 <- env.repository.list(userId2)
        yield assertTrue(rows1.size == 1, rows1.head.password.sameElements(pass(1)), rows2.size == 1)
      },

      // Concurrency: two simultaneous changes must not both insert. Without the per-user lock both
      // delete an empty set and both insert, leaving the user with two passwords.
      test("concurrent create leaves exactly one password") {
        for
          _ <- ZIO.collectAllPar(List(
            env.repository.create(userId1, pass(1), salt(1)),
            env.repository.create(userId1, pass(2), salt(2)),
          ))
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 1)
      },
    )

object PasswordRepositorySpec:
  case class Env(repository: PasswordRepository)
