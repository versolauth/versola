package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, Email, Phone}
import zio.*
import zio.json.*
import zio.test.*

import java.util.UUID

trait UserIntentRepositorySpec extends DatabaseSpecBase[UserIntentRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val user1 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  val user2 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  val client1 = ClientId("client-1")
  val client2 = ClientId("client-2")
  val changePhone = UserIntent.ChangePhone(Phone("+77011234567"))
  val otherPhone = UserIntent.ChangePhone(Phone("+77011234568"))
  val changeEmail = UserIntent.ChangeEmail(Email("new@example.com"))

  val ttl = 5.minutes

  def testCases(env: UserIntentRepositorySpec.Env): List[Spec[UserIntentRepositorySpec.Env & Scope, Any]] =
    List(
      test("an intent is consumed once") {
        for
          _ <- env.repository.create(user1, client1, changePhone, ttl)
          first <- env.repository.consume(user1, client1, changePhone)
          second <- env.repository.consume(user1, client1, changePhone)
        yield assertTrue(first, !second)
      },
      test("an intent of another user, client, kind or value does not match, and is not burned by the attempt") {
        for
          _ <- env.repository.create(user1, client1, changePhone, ttl)
          otherUser <- env.repository.consume(user2, client1, changePhone)
          otherClient <- env.repository.consume(user1, client2, changePhone)
          otherKind <- env.repository.consume(user1, client1, UserIntent.ChangeEmail(Email("+77011234567")))
          otherValue <- env.repository.consume(user1, client1, otherPhone)
          matching <- env.repository.consume(user1, client1, changePhone)
        yield assertTrue(!otherUser, !otherClient, !otherKind, !otherValue, matching)
      },
      test("a newer intent of the same user replaces the older one") {
        for
          _ <- env.repository.create(user1, client1, changePhone, ttl)
          _ <- env.repository.create(user1, client1, changeEmail, ttl)
          old <- env.repository.consume(user1, client1, changePhone)
          current <- env.repository.consume(user1, client1, changeEmail)
        yield assertTrue(!old, current)
      },
      test("an expired intent is not consumed") {
        for
          _ <- env.repository.create(user1, client1, changePhone, ttl)
          _ <- TestClock.adjust(ttl + 1.second)
          found <- env.repository.consume(user1, client1, changePhone)
        yield assertTrue(!found)
      },
      test("concurrent consume attempts - only one succeeds") {
        for
          _ <- env.repository.create(user1, client1, changePhone, ttl)
          results <- ZIO.collectAllPar(List.fill(10)(env.repository.consume(user1, client1, changePhone)))
        yield assertTrue(results.count(identity) == 1)
      },
    )

object UserIntentRepositorySpec:
  case class Env(repository: UserIntentRepository)
