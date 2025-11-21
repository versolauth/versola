package versola.auth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.model.Email
import versola.util.DatabaseSpecBase
import zio.test.*

trait BanRepositorySpec extends DatabaseSpecBase[BanRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val email1 = Email("user1@example.com")
  val email2 = Email("user2@example.com")
  val email3 = Email("user3@example.com")

  override def testCases(env: BanRepositorySpec.Env) =
    List(
      getAllTests(env),
      isBannedTests(env),
      banTests(env),
    )

  def getAllTests(env: BanRepositorySpec.Env) =
    suite("getAll")(
      test("return empty set when no bans exist") {
        for
          result <- env.banRepository.getAll
        yield assertTrue(result.isEmpty)
      },
      test("return all banned emails") {
        for
          _ <- env.banRepository.ban(email1)
          _ <- env.banRepository.ban(email2)
          result <- env.banRepository.getAll
        yield assertTrue(
          result == Set(email1, email2)
        )
      },
    )

  def isBannedTests(env: BanRepositorySpec.Env) =
    suite("isBanned")(
      test("return false when phone is not banned") {
        for
          result <- env.banRepository.isBanned(email1)
        yield assertTrue(!result)
      },
      test("return true when phone is banned") {
        for
          _ <- env.banRepository.ban(email1)
          result <- env.banRepository.isBanned(email1)
        yield assertTrue(result)
      }
    )

  def banTests(env: BanRepositorySpec.Env) =
    suite("ban")(
      test("successfully ban a new phone") {
        for
          _ <- env.banRepository.ban(email1)
          isBanned <- env.banRepository.isBanned(email1)
          allBans <- env.banRepository.getAll
        yield assertTrue(
          isBanned,
          allBans.contains(email1)
        )
      },
      test("handle duplicate ban gracefully") {
        for
          _ <- env.banRepository.ban(email1)
          _ <- env.banRepository.ban(email1)
          allBans <- env.banRepository.getAll
          isBanned <- env.banRepository.isBanned(email1)
        yield assertTrue(
          isBanned,
          allBans == Set(email1)
        )
      },
      test("ban multiple different phones") {
        for
          _ <- env.banRepository.ban(email1)
          _ <- env.banRepository.ban(email2)
          _ <- env.banRepository.ban(email3)
          allBans <- env.banRepository.getAll
          isBanned1 <- env.banRepository.isBanned(email1)
          isBanned2 <- env.banRepository.isBanned(email2)
          isBanned3 <- env.banRepository.isBanned(email3)
        yield assertTrue(
          isBanned1,
          isBanned2,
          isBanned3,
          allBans == Set(email1, email2, email3)
        )
      },
    )

object BanRepositorySpec:
  case class Env(banRepository: BanRepository)
