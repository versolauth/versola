package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.util.UUID

/** `UserRepository.delete` has to reach every table keyed by the user, and nothing but a leftover
  * row -- a refresh token that still redeems, a passkey that still signs in -- would notice one
  * it missed. The rows are seeded raw, by the storage's runner through [[UserPurgeSpec.Fixtures]], rather than through the
  * owning repositories: what is asserted is which tables the purge reaches, not how those repositories write.
  */
trait UserPurgeSpec extends DatabaseSpecBase[UserPurgeSpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import UserPurgeSpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    List(
      test("delete takes the sessions, tokens, codes, conversations, passkeys, passwords, roles, consents and throttling with the account") {
        for
          _ <- env.fixtures.seed(one)
          _ <- env.fixtures.seed(two)
          before <- env.fixtures.rowsOf(one)
          _ <- env.repository.delete(one.userId)
          after <- env.fixtures.rowsOf(one)
          other <- env.fixtures.rowsOf(two)
        yield assertTrue(before == seededRows).label("the seed has to cover every table or the purge is not being tested") &&
          assertTrue(after.values.forall(_ == 0L)).label(s"rows left after the purge: ${after.filter(_._2 > 0)}") &&
          assertTrue(other == seededRows).label("the other account's rows are not for this purge to touch")
      },
    )

object UserPurgeSpec:
  case class Env(repository: UserRepository, fixtures: Fixtures)

  case class Identity(userId: UserId, email: String, phone: String, tag: String)

  val one: Identity = Identity(
    userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f")),
    email = "purge1@example.com",
    phone = "+12025550001",
    tag = "one",
  )

  val two: Identity = Identity(
    userId = UserId(UUID.fromString("58c5fe37-6c39-43d1-ae8c-3fab32e1ed72")),
    email = "purge2@example.com",
    phone = "+12025550002",
    tag = "two",
  )

  /** What a seed leaves for one identity, by the table it is kept in: the account and everything keyed by it. */
  val seededRows: Map[String, Long] = Map(
    "users" -> 1L,
    "user_roles" -> 1L,
    "user_consents" -> 1L,
    "user_agents" -> 1L,
    "sso_sessions" -> 1L,
    "refresh_tokens" -> 1L,
    "authorization_codes" -> 1L,
    "auth_conversations" -> 2L,
    "passkeys" -> 1L,
    "user_passwords" -> 1L,
    "challenge_throttle" -> 3L,
  )

  /** The rows of an account in every table keyed by it, written and counted without the repositories that own them: the
    * storage's runner supplies it. */
  trait Fixtures:
    /** Writes [[seededRows]] for the identity. */
    def seed(identity: Identity): Task[Unit]

    /** Rows the tables still hold for the identity, keyed by table. */
    def rowsOf(identity: Identity): Task[Map[String, Long]]
