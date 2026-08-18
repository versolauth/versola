package versola.oauth.consent

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

trait ConsentRepositorySpec extends DatabaseSpecBase[ConsentRepositorySpec.Env]:
  self: ZIOSpec[com.augustnagro.magnum.magzio.TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("00000000-0000-7000-8000-000000000001"))
  val userId2 = UserId(UUID.fromString("00000000-0000-7000-8000-000000000002"))
  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  // Postgres stores microseconds, so a nanosecond-precision Instant would not round-trip.
  val grantedAt = Instant.parse("2024-01-01T00:00:00Z").truncatedTo(ChronoUnit.MICROS)
  val expiresAt = grantedAt.plusSeconds(30 * 86400)

  val record1 = ConsentRecord(
    userId = userId1,
    clientId = clientId1,
    scope = Set(ScopeToken.OpenId, ScopeToken("profile")),
    grantedAt = grantedAt,
    expiresAt = None,
  )

  def testCases(env: ConsentRepositorySpec.Env): List[Spec[ConsentRepositorySpec.Env & zio.Scope, Any]] =
    List(
      test("find returns None when nothing was granted") {
        for found <- env.repository.find(userId1, clientId1)
        yield assertTrue(found.isEmpty)
      },
      test("upsert then find round-trips the grant") {
        for
          _ <- env.repository.upsert(record1)
          found <- env.repository.find(userId1, clientId1)
        yield assertTrue(found.contains(record1))
      },
      test("upsert round-trips an expiry") {
        val expiring = record1.copy(expiresAt = Some(expiresAt))
        for
          _ <- env.repository.upsert(expiring)
          found <- env.repository.find(userId1, clientId1)
        yield assertTrue(found.contains(expiring))
      },
      test("upsert replaces the previous grant for the same user and client") {
        val widened = record1.copy(
          scope = Set(ScopeToken.OpenId, ScopeToken("profile"), ScopeToken("email")),
          grantedAt = grantedAt.plusSeconds(60),
          expiresAt = Some(expiresAt),
        )
        for
          _ <- env.repository.upsert(record1)
          _ <- env.repository.upsert(widened)
          found <- env.repository.find(userId1, clientId1)
        yield assertTrue(found.contains(widened))
      },
      test("grants are scoped to a single user and client") {
        val otherClient = record1.copy(clientId = clientId2, scope = Set(ScopeToken.OpenId))
        val otherUser = record1.copy(userId = userId2, scope = Set(ScopeToken("email")))
        for
          _ <- env.repository.upsert(record1)
          _ <- env.repository.upsert(otherClient)
          _ <- env.repository.upsert(otherUser)
          sameUserOtherClient <- env.repository.find(userId1, clientId2)
          otherUserSameClient <- env.repository.find(userId2, clientId1)
          untouched <- env.repository.find(userId1, clientId1)
        yield assertTrue(
          sameUserOtherClient.contains(otherClient),
          otherUserSameClient.contains(otherUser),
          untouched.contains(record1),
        )
      },
      test("delete removes only the addressed grant") {
        val otherClient = record1.copy(clientId = clientId2)
        for
          _ <- env.repository.upsert(record1)
          _ <- env.repository.upsert(otherClient)
          _ <- env.repository.delete(userId1, clientId1)
          deleted <- env.repository.find(userId1, clientId1)
          kept <- env.repository.find(userId1, clientId2)
        yield assertTrue(deleted.isEmpty, kept.contains(otherClient))
      },
      test("delete is a no-op when nothing was granted") {
        for
          _ <- env.repository.delete(userId1, clientId1)
          found <- env.repository.find(userId1, clientId1)
        yield assertTrue(found.isEmpty)
      },
      test("an empty granted scope round-trips") {
        val empty = record1.copy(scope = Set.empty)
        for
          _ <- env.repository.upsert(empty)
          found <- env.repository.find(userId1, clientId1)
        yield assertTrue(found.contains(empty))
      },
    )

object ConsentRepositorySpec:
  case class Env(repository: ConsentRepository)
