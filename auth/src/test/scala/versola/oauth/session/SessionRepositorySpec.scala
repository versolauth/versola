package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{SessionId, SessionRecord, UserAgentInfo}
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
    userAgent = UserAgentInfo("desktop", Some("Windows 10 / 11"), Some("Chrome"), Some("125")),
    createdAt = Instant.EPOCH,
    amr = Map.empty,
  )

  val session2 = SessionRecord(
    userId = userId2,
    clientId = clientId2,
    userAgent = UserAgentInfo("unknown", None, None, None),
    createdAt = Instant.EPOCH,
    amr = Map.empty,
  )

  def testCases(env: SessionRepositorySpec.Env): List[Spec[SessionRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find session") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.contains(session1))
      },
      test("find returns None for non-existent session") {
        for
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns None for expired session") {
        for
          _ <- env.repository.create(sessionId1, session1, 0.seconds, None)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("create multiple sessions with different IDs") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None)
          _ <- env.repository.create(sessionId2, session2, ttl, None)
          found1 <- env.repository.findSession(sessionId1)
          found2 <- env.repository.findSession(sessionId2)
        yield assertTrue(
          found1.contains(session1),
          found2.contains(session2),
        )
      },
      test("session expires after TTL") {
        for
          _ <- env.repository.create(sessionId1, session1, 2.minutes, None)
          foundBefore <- env.repository.findSession(sessionId1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findSession(sessionId1)
        yield assertTrue(
          foundBefore.contains(session1),
          foundAfter.isEmpty,
        )
      },
      test("idle session expires after idle TTL even though absolute TTL remains") {
        for
          _ <- env.repository.create(sessionId1, session1, 1.hour, Some(2.minutes))
          foundBefore <- env.repository.findSession(sessionId1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findSession(sessionId1)
        yield assertTrue(
          foundBefore.contains(session1),
          foundAfter.isEmpty,
        )
      },
      test("idle session still dies at absolute TTL despite a longer idle window") {
        for
          _ <- env.repository.create(sessionId1, session1, 2.minutes, Some(1.hour))
          _ <- TestClock.adjust(3.minutes)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("prolongIdle slides idle expiry forward") {
        for
          _ <- env.repository.create(sessionId1, session1, 1.hour, Some(5.minutes))
          _ <- TestClock.adjust(4.minutes)
          _ <- env.repository.prolongIdle(sessionId1, 5.minutes)
          _ <- TestClock.adjust(4.minutes)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.contains(session1))
      },
      test("prolongIdle does not promote a session created without an idle window") {
        for
          _ <- env.repository.create(sessionId1, session1, 1.hour, None)
          _ <- env.repository.prolongIdle(sessionId1, 1.minute)
          _ <- TestClock.adjust(2.minutes)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.contains(session1))
      },
      test("findByUserId returns active sessions for user") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None)
          _ <- env.repository.create(sessionId2, session2, ttl, None)
          _ <- env.repository.create(sessionId3, session1.copy(clientId = clientId2), ttl, None)
          results <- env.repository.findByUserId(userId1)
        yield assertTrue(
          results.size == 2,
          results.forall(_.userId == userId1),
        )
      },
      test("findByUserId does not return expired sessions") {
        for
          _ <- env.repository.create(sessionId1, session1, 0.seconds, None)
          _ <- TestClock.adjust(1.second)
          results <- env.repository.findByUserId(userId1)
        yield assertTrue(results.isEmpty)
      },
      test("invalidateByUserId removes all user sessions") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None)
          _ <- env.repository.create(sessionId3, session1.copy(clientId = clientId2), ttl, None)
          before <- env.repository.findByUserId(userId1)
          _ <- env.repository.invalidateByUserId(userId1)
          after <- env.repository.findByUserId(userId1)
        yield assertTrue(
          before.size == 2,
          after.isEmpty,
        )
      },
      test("invalidateByUserId does not affect other users") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None)
          _ <- env.repository.create(sessionId2, session2, ttl, None)
          _ <- env.repository.invalidateByUserId(userId1)
          session1After <- env.repository.findSession(sessionId1)
          session2After <- env.repository.findSession(sessionId2)
        yield assertTrue(
          session1After.isEmpty,
          session2After.isDefined,
        )
      },
    )

object SessionRepositorySpec:
  case class Env(repository: SessionRepository)
