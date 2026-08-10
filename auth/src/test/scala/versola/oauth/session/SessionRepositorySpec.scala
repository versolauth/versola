package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.model.AccessToken
import versola.oauth.session.model.{ClientEntry, PriorSession, PublicSessionId, RefreshTokenRecord, SessionId, SessionRecord, UserAgentId}
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
  val atomicSessionId = MAC(Array.fill(32)(77.toByte))
  val atomicTokenId   = MAC(Array.fill(32)(78.toByte))

  val publicId1      = PublicSessionId("public-session-1")
  val publicId2      = PublicSessionId("public-session-2")
  val publicId3      = PublicSessionId("public-session-3")
  val atomicPublicId = PublicSessionId("public-session-atomic")

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val ttl = 5.minutes

  val userAgentId1 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
  val userAgentId2 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))

  val session1 = SessionRecord(
    userId = userId1,
    clients = List(ClientEntry(clientId1, Instant.EPOCH)),
    userAgentId = userAgentId1,
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = publicId1,
  )

  val session2 = SessionRecord(
    userId = userId2,
    clients = List(ClientEntry(clientId2, Instant.EPOCH)),
    userAgentId = userAgentId2,
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = publicId2,
  )

  def testCases(env: SessionRepositorySpec.Env): List[Spec[SessionRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find session") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None, None)
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
          _ <- env.repository.create(sessionId1, session1, 0.seconds, None, None)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("create multiple sessions with different IDs") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None, None)
          _ <- env.repository.create(sessionId2, session2, ttl, None, None)
          found1 <- env.repository.findSession(sessionId1)
          found2 <- env.repository.findSession(sessionId2)
        yield assertTrue(
          found1.contains(session1),
          found2.contains(session2),
        )
      },
      test("session expires after TTL") {
        for
          _ <- env.repository.create(sessionId1, session1, 2.minutes, None, None)
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
          _ <- env.repository.create(sessionId1, session1, 1.hour, Some(2.minutes), None)
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
          _ <- env.repository.create(sessionId1, session1, 2.minutes, Some(1.hour), None)
          _ <- TestClock.adjust(3.minutes)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.isEmpty)
      },
      test("prolongIdle slides idle expiry forward") {
        for
          _ <- env.repository.create(sessionId1, session1, 1.hour, Some(5.minutes), None)
          _ <- TestClock.adjust(4.minutes)
          _ <- env.repository.prolongIdle(sessionId1, 5.minutes)
          _ <- TestClock.adjust(4.minutes)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.contains(session1))
      },
      test("prolongIdle does not promote a session created without an idle window") {
        for
          _ <- env.repository.create(sessionId1, session1, 1.hour, None, None)
          _ <- env.repository.prolongIdle(sessionId1, 1.minute)
          _ <- TestClock.adjust(2.minutes)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.contains(session1))
      },
      test("findByUserId returns active sessions for user") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None, None)
          _ <- env.repository.create(sessionId2, session2, ttl, None, None)
          _ <- env.repository.create(sessionId3, session1.copy(clients = List(ClientEntry(clientId2, Instant.EPOCH)), publicId = publicId3), ttl, None, None)
          results <- env.repository.findByUserId(userId1)
        yield assertTrue(
          results.size == 2,
          results.forall(_.userId == userId1),
        )
      },
      test("findByUserId does not return expired sessions") {
        for
          _ <- env.repository.create(sessionId1, session1, 0.seconds, None, None)
          _ <- TestClock.adjust(1.second)
          results <- env.repository.findByUserId(userId1)
        yield assertTrue(results.isEmpty)
      },
      test("invalidateByUserId removes all user sessions") {
        for
          _ <- env.repository.create(sessionId1, session1, ttl, None, None)
          _ <- env.repository.create(sessionId3, session1.copy(clients = List(ClientEntry(clientId2, Instant.EPOCH)), publicId = publicId3), ttl, None, None)
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
          _ <- env.repository.create(sessionId1, session1, ttl, None, None)
          _ <- env.repository.create(sessionId2, session2, ttl, None, None)
          _ <- env.repository.invalidateByUserId(userId1)
          session1After <- env.repository.findSession(sessionId1)
          session2After <- env.repository.findSession(sessionId2)
        yield assertTrue(
          session1After.isEmpty,
          session2After.isDefined,
        )
      },
      test("invalidateByUserId removes sessions and refresh tokens together") {
        for
          now    <- Clock.instant
          record  = RefreshTokenRecord(
            sessionId            = atomicSessionId,
            publicSessionId      = atomicPublicId,
            accessToken          = AccessToken(Array.fill(16)(1.toByte)),
            userId               = userId1,
            clientId             = clientId1,
            audience             = List.empty,
            scope                = Set(ScopeToken("read")),
            issuedAt             = now,
            expiresAt            = now.plusSeconds(30.days.toSeconds),
            requestedClaims      = None,
            uiLocales            = None,
            nonce                = None,
            previousRefreshToken = None,
            amr                  = Set(AuthMethodRef.pwd),
            authTime             = now,
            acr                  = None,
          )
          _            <- env.repository.create(atomicSessionId, session1, 5.minutes, None, None)
          _            <- env.repository.createRefreshToken(atomicTokenId, record)
          _            <- env.repository.invalidateByUserId(userId1)
          sessionAfter <- env.repository.findSession(atomicSessionId)
          tokenAfter   <- env.repository.findToken(atomicTokenId)
        yield assertTrue(sessionAfter.isEmpty, tokenAfter.isEmpty)
      },
      test("invalidate returns the deleted session and removes it") {
        for
          _      <- env.repository.create(sessionId1, session1, ttl, None, None)
          result <- env.repository.invalidate(sessionId1)
          after  <- env.repository.findSession(sessionId1)
        yield assertTrue(result.contains(session1), after.isEmpty)
      },
      test("invalidate returns None for a non-existent session") {
        for result <- env.repository.invalidate(sessionId1)
        yield assertTrue(result.isEmpty)
      },
      test("invalidate returns None and is a no-op for an already expired session") {
        for
          _      <- env.repository.create(sessionId1, session1, 0.seconds, None, None)
          _      <- TestClock.adjust(1.second)
          result <- env.repository.invalidate(sessionId1)
        yield assertTrue(result.isEmpty)
      },
      test("invalidate removes associated refresh tokens") {
        for
          now   <- Clock.instant
          record = RefreshTokenRecord(
            sessionId            = atomicSessionId,
            publicSessionId      = atomicPublicId,
            accessToken          = AccessToken(Array.fill(16)(1.toByte)),
            userId               = userId1,
            clientId             = clientId1,
            audience             = List.empty,
            scope                = Set(ScopeToken("read")),
            issuedAt             = now,
            expiresAt            = now.plusSeconds(30.days.toSeconds),
            requestedClaims      = None,
            uiLocales            = None,
            nonce                = None,
            previousRefreshToken = None,
            amr                  = Set(AuthMethodRef.pwd),
            authTime             = now,
            acr                  = None,
          )
          _          <- env.repository.create(atomicSessionId, session1, 5.minutes, None, None)
          _          <- env.repository.createRefreshToken(atomicTokenId, record)
          _          <- env.repository.invalidate(atomicSessionId)
          tokenAfter <- env.repository.findToken(atomicTokenId)
        yield assertTrue(tokenAfter.isEmpty)
      },
      test("invalidateByPublicId returns the session id and record and removes it") {
        for
          _      <- env.repository.create(sessionId1, session1, ttl, None, None)
          result <- env.repository.invalidateByPublicId(publicId1)
          after  <- env.repository.findSession(sessionId1)
        yield assertTrue(
          result.exists { case (id, record) => id.sameElements(sessionId1) && record == session1 },
          after.isEmpty,
        )
      },
      test("invalidateByPublicId returns None for a non-existent public id") {
        for result <- env.repository.invalidateByPublicId(publicId1)
        yield assertTrue(result.isEmpty)
      },
      test("invalidateByPublicId invalidates even an already expired session") {
        for
          _      <- env.repository.create(sessionId1, session1, 0.seconds, None, None)
          _      <- TestClock.adjust(1.second)
          result <- env.repository.invalidateByPublicId(publicId1)
        yield assertTrue(result.exists { case (id, record) => id.sameElements(sessionId1) && record == session1 })
      },
      test("invalidateByPublicId removes associated refresh tokens") {
        for
          now   <- Clock.instant
          record = RefreshTokenRecord(
            sessionId            = atomicSessionId,
            publicSessionId      = atomicPublicId,
            accessToken          = AccessToken(Array.fill(16)(1.toByte)),
            userId               = userId1,
            clientId             = clientId1,
            audience             = List.empty,
            scope                = Set(ScopeToken("read")),
            issuedAt             = now,
            expiresAt            = now.plusSeconds(30.days.toSeconds),
            requestedClaims      = None,
            uiLocales            = None,
            nonce                = None,
            previousRefreshToken = None,
            amr                  = Set(AuthMethodRef.pwd),
            authTime             = now,
            acr                  = None,
          )
          _          <- env.repository.create(atomicSessionId, session1.copy(publicId = atomicPublicId), 5.minutes, None, None)
          _          <- env.repository.createRefreshToken(atomicTokenId, record)
          _          <- env.repository.invalidateByPublicId(atomicPublicId)
          tokenAfter <- env.repository.findToken(atomicTokenId)
        yield assertTrue(tokenAfter.isEmpty)
      },
      test("registerClient adds a client id to the session") {
        for
          _     <- env.repository.create(sessionId1, session1, ttl, None, None)
          _     <- env.repository.registerClient(sessionId1, clientId2)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.exists(_.clients.map(_.clientId) == List(clientId1, clientId2)))
      },
      test("registerClient is idempotent") {
        for
          _     <- env.repository.create(sessionId1, session1, ttl, None, None)
          _     <- env.repository.registerClient(sessionId1, clientId1)
          found <- env.repository.findSession(sessionId1)
        yield assertTrue(found.exists(_.clients.map(_.clientId) == List(clientId1)))
      },
      test("create carries over prior session's client ids on rotation") {
        for
          _     <- env.repository.create(sessionId1, session1, ttl, None, None)
          _     <- env.repository.registerClient(sessionId1, clientId2)
          _     <- env.repository.create(sessionId2, session2, ttl, None, Some(PriorSession.Invalidate(sessionId1)))
          found <- env.repository.findSession(sessionId2)
        yield assertTrue(found.exists(_.clients.map(_.clientId).toSet == Set(clientId1, clientId2)))
      },
    )

object SessionRepositorySpec:
  case class Env(repository: SessionRepository)
