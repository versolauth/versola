package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

trait SessionRepositorySpec extends DatabaseSpecBase[SessionRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val sessionId2 = MAC(Array.fill(32)(2.toByte))
  val sessionId3 = MAC(Array.fill(32)(3.toByte))

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val ttl = 5.minutes

  val session1 = SessionRecord(
    userId = userId1,
    clientId = clientId1,
    userAgent = Some("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/125.0"),
    createdAt = Instant.EPOCH,
  )

  val session2 = SessionRecord(
    userId = userId2,
    clientId = clientId2,
    userAgent = None,
    createdAt = Instant.EPOCH,
  )

  def testCases(env: SessionRepositorySpec.Env): List[Spec[SessionRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find session") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl)
          found <- env.repository.find(sessionId1)
        yield assertTrue(found.contains(session1))
      },
      test("find returns None for non-existent session") {
        for
          found <- env.repository.find(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns None for expired session") {
        for
          _ <- env.repository.create(sessionId1, session1, 0.seconds)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.find(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("create multiple sessions with different IDs") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl)
          _ <- env.repository.create(sessionId2, session2, ttl)
          found1 <- env.repository.find(sessionId1)
          found2 <- env.repository.find(sessionId2)
        yield assertTrue(
          found1.contains(session1),
          found2.contains(session2),
        )
      },
      test("session expires after TTL") {
        for
          _ <- env.repository.create(sessionId1, session1, 2.minutes)
          foundBefore <- env.repository.find(sessionId1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.find(sessionId1)
        yield assertTrue(
          foundBefore.contains(session1),
          foundAfter.isEmpty,
        )
      },
      test("findByUser returns active sessions for user") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl)
          _ <- env.repository.create(sessionId2, session2, ttl)
          _ <- env.repository.create(sessionId3, session1.copy(clientId = clientId2), ttl)
          results <- env.repository.findByUser(userId1)
        yield assertTrue(
          results.size == 2,
          results.map(_._1).toSet == Set(sessionId1, sessionId3),
          results.forall(_._2.userId == userId1),
        )
      },
      test("findByUser does not return expired sessions") {
        for
          _ <- env.repository.create(sessionId1, session1, 0.seconds)
          _ <- TestClock.adjust(1.second)
          results <- env.repository.findByUser(userId1)
        yield assertTrue(results.isEmpty)
      },
      test("invalidate removes session from findByUser results") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl)
          before <- env.repository.findByUser(userId1)
          _ <- env.repository.invalidate(sessionId1)
          after <- env.repository.findByUser(userId1)
        yield assertTrue(
          before.size == 1,
          after.isEmpty,
        )
      },
      test("invalidate makes session invisible via find") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl)
          before <- env.repository.find(sessionId1)
          _ <- env.repository.invalidate(sessionId1)
          after <- env.repository.find(sessionId1)
        yield assertTrue(
          before.isDefined,
          after.isEmpty,
        )
      },
    )

object SessionRepositorySpec:
  case class Env(repository: SessionRepository)
