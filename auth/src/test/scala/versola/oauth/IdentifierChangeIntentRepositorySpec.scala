package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.oauth.identifier.{IdentifierChangeIntentRepository, IdentifierChangeKind}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, Email, Phone}
import zio.*
import zio.test.*

import java.util.UUID

trait IdentifierChangeIntentRepositorySpec extends DatabaseSpecBase[IdentifierChangeIntentRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val user1 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  val user2 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  val client1 = ClientId("client-1")
  val client2 = ClientId("client-2")
  val phone = Right(Phone("+77011234567"))
  val otherPhone = Right(Phone("+77011234568"))
  val email = Left(Email("new@example.com"))

  val ttl = 5.minutes

  def testCases(env: IdentifierChangeIntentRepositorySpec.Env): List[Spec[IdentifierChangeIntentRepositorySpec.Env & Scope, Any]] =
    List(
      test("an intent is consumed once") {
        for
          _ <- env.repository.create(user1, client1, IdentifierChangeKind.Phone, phone, ttl)
          first <- env.repository.consume(user1, client1, IdentifierChangeKind.Phone, phone)
          second <- env.repository.consume(user1, client1, IdentifierChangeKind.Phone, phone)
        yield assertTrue(first, !second)
      },
      test("an intent of another user, client, kind or value does not match, and is not burned by the attempt") {
        for
          _ <- env.repository.create(user1, client1, IdentifierChangeKind.Phone, phone, ttl)
          otherUser <- env.repository.consume(user2, client1, IdentifierChangeKind.Phone, phone)
          otherClient <- env.repository.consume(user1, client2, IdentifierChangeKind.Phone, phone)
          otherKind <- env.repository.consume(user1, client1, IdentifierChangeKind.Email, phone)
          otherValue <- env.repository.consume(user1, client1, IdentifierChangeKind.Phone, otherPhone)
          matching <- env.repository.consume(user1, client1, IdentifierChangeKind.Phone, phone)
        yield assertTrue(!otherUser, !otherClient, !otherKind, !otherValue, matching)
      },
      test("a newer intent of the same user replaces the older one") {
        for
          _ <- env.repository.create(user1, client1, IdentifierChangeKind.Phone, phone, ttl)
          _ <- env.repository.create(user1, client1, IdentifierChangeKind.Email, email, ttl)
          old <- env.repository.consume(user1, client1, IdentifierChangeKind.Phone, phone)
          current <- env.repository.consume(user1, client1, IdentifierChangeKind.Email, email)
        yield assertTrue(!old, current)
      },
      test("an expired intent is not consumed") {
        for
          _ <- env.repository.create(user1, client1, IdentifierChangeKind.Phone, phone, ttl)
          _ <- TestClock.adjust(ttl + 1.second)
          found <- env.repository.consume(user1, client1, IdentifierChangeKind.Phone, phone)
        yield assertTrue(!found)
      },
      test("concurrent consume attempts - only one succeeds") {
        for
          _ <- env.repository.create(user1, client1, IdentifierChangeKind.Phone, phone, ttl)
          results <- ZIO.collectAllPar(List.fill(10)(env.repository.consume(user1, client1, IdentifierChangeKind.Phone, phone)))
        yield assertTrue(results.count(identity) == 1)
      },
    )

object IdentifierChangeIntentRepositorySpec:
  case class Env(repository: IdentifierChangeIntentRepository)
