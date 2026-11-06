package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.model.UserAgentData
import versola.oauth.session.model.{
  ClientEntry,
  PublicSessionId,
  RefreshTokenFamilyId,
  RefreshTokenRecord,
  SessionId,
  SessionRecord,
  UserAgentDetails,
  UserAgentId,
  UserAgentRecord,
}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

trait UserAgentRepositorySpec extends DatabaseSpecBase[UserAgentRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val userAgentId1 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
  val userAgentId2 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000c2"))

  val details1 = UserAgentDetails(platform = Some("desktop"), os = Some("macOS 14"), browser = Some("Chrome"), version = Some("120"))
  val details2 = UserAgentDetails(platform = Some("ios"), os = Some("iOS 17"), browser = Some("Safari"), version = Some("17"))

  val data1 = UserAgentData(userAgent = Some("ua-1"), userId = userId1, details = details1)
  val data2 = UserAgentData(userAgent = Some("ua-2"), userId = userId2, details = details2)

  val ttl = 30.days

  val sessionMac1 = MAC(Array.fill(32)(1.toByte))
  val sessionMac2 = MAC(Array.fill(32)(2.toByte))
  val tokenMac1 = MAC(Array.fill(32)(11.toByte))
  val tokenMac2 = MAC(Array.fill(32)(12.toByte))

  private def session(userId: UserId, userAgentId: UserAgentId, publicId: String) =
    SessionRecord(
      userId = userId,
      clients = List(ClientEntry(ClientId("client-1"), Instant.EPOCH)),
      userAgentId = userAgentId,
      createdAt = Instant.EPOCH,
      amr = Map.empty,
      publicId = PublicSessionId(publicId),
      expiresAt = Instant.EPOCH,
    )

  private def refreshToken(userId: UserId, sessionMac: MAC.Of[SessionId], publicId: String, now: Instant, family: String) =
    RefreshTokenRecord(
      familyId = RefreshTokenFamilyId(family),
      sessionId = sessionMac,
      publicSessionId = PublicSessionId(publicId),
      userId = userId,
      clientId = ClientId("client-1"),
      audience = List.empty,
      authorizationDetails = None,
      scope = Set(ScopeToken("read")),
      issuedAt = now,
      expiresAt = now.plusSeconds(30.days.toSeconds),
      requestedClaims = None,
      uiLocales = None,
      nonce = None,
      amr = Set(AuthMethodRef.pwd),
      authTime = now,
      acr = None,
      cnf = None,
    )

  def testCases(env: UserAgentRepositorySpec.Env): List[Spec[UserAgentRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find a user agent") {
        for
          _ <- env.repository.create(userAgentId1, data1, ttl)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(found.contains(details1))
      },
      test("find returns None for a non-existent user agent") {
        for found <- env.repository.find(userAgentId1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns None for an expired user agent") {
        for
          _ <- env.repository.create(userAgentId1, data1, 0.seconds)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(found.isEmpty)
      },
      test("create is idempotent when the id already exists") {
        for
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.repository.create(userAgentId1, data2, ttl)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(found.contains(details1))
      },
      test("findMany returns details for known, non-expired ids only") {
        for
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.repository.create(userAgentId2, data2, 0.seconds)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.findMany(List(userAgentId1, userAgentId2))
        yield assertTrue(found == Map(userAgentId1 -> details1))
      },
      test("findMany returns an empty map for an empty id list") {
        for found <- env.repository.findMany(List.empty)
        yield assertTrue(found.isEmpty)
      },
      test("findMany returns an empty map when no ids are known") {
        for found <- env.repository.findMany(List(userAgentId1, userAgentId2))
        yield assertTrue(found.isEmpty)
      },
      test("touch slides the expiry forward and refreshes the details") {
        for
          _ <- env.repository.create(userAgentId1, data1, 1.hour)
          _ <- TestClock.adjust(30.minutes)
          updated <- env.repository.touch(userAgentId1, data1.copy(details = details2), 1.hour)
          _ <- TestClock.adjust(45.minutes)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(updated, found.contains(details2))
      },
      test("touch returns false and is a no-op for a non-existent id") {
        for
          updated <- env.repository.touch(userAgentId1, data1, ttl)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(!updated, found.isEmpty)
      },
      test("touch still finds and refreshes a row that expired but was not yet cleaned up") {
        // Expiry itself is only enforced by find/findMany and by the periodic cleanup job that
        // deletes stale rows; touch has no expiry filter, so a row that's merely past its
        // expires_at (but not yet physically removed) is still touchable.
        for
          _ <- env.repository.create(userAgentId1, data1, 0.seconds)
          _ <- TestClock.adjust(1.second)
          updated <- env.repository.touch(userAgentId1, data1.copy(details = details2), ttl)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(updated, found.contains(details2))
      },
      test("findByUserId returns everything the row stores, for the user's own non-expired user agents only") {
        for
          now <- Clock.instant
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.repository.create(userAgentId2, data2, ttl)
          expiredId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000c3"))
          _ <- env.repository.create(expiredId, data1, 0.seconds)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.findByUserId(userId1)
        yield assertTrue(
          found == List(
            UserAgentRecord(
              id = userAgentId1,
              userAgent = Some("ua-1"),
              platform = details1.platform,
              os = details1.os,
              browser = details1.browser,
              version = details1.version,
              createdAt = now,
              expiresAt = now.plusSeconds(ttl.toSeconds),
            ),
          ),
        )
      },
      test("findByUserId lists the most recently signed-in user agent first") {
        val older = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000c4"))
        for
          _ <- env.repository.create(older, data1, 1.hour)
          _ <- env.repository.create(userAgentId1, data1, 2.hours)
          found <- env.repository.findByUserId(userId1)
        yield assertTrue(found.map(_.id) == List(userAgentId1, older))
      },
      test("findByUserId follows a user agent that was re-bound to another user by touch") {
        for
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.repository.touch(userAgentId1, data1.copy(userId = userId2), ttl)
          first <- env.repository.findByUserId(userId1)
          second <- env.repository.findByUserId(userId2)
        yield assertTrue(first.isEmpty, second.map(_.id) == List(userAgentId1))
      },
      test("forget deletes the user agent, expires its session and the refresh tokens bound to it, and leaves other devices alone") {
        for
          now <- Clock.instant
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.repository.create(userAgentId2, data1.copy(userAgent = Some("ua-other")), ttl)
          _ <- env.sessions.create(sessionMac1, session(userId1, userAgentId1, "forgotten"), 1.hour, None, None)
          _ <- env.sessions.create(sessionMac2, session(userId1, userAgentId2, "kept"), 1.hour, None, None)
          _ <- env.sessions.createRefreshToken(tokenMac1, None, refreshToken(userId1, sessionMac1, "forgotten", now, "family-forgotten"), None)
          _ <- env.sessions.createRefreshToken(tokenMac2, None, refreshToken(userId1, sessionMac2, "kept", now, "family-kept"), None)
          forgotten <- env.repository.forget(userAgentId1, userId1)
          agents <- env.repository.findByUserId(userId1)
          sessions <- env.sessions.findByUserId(userId1)
          forgottenToken <- env.sessions.findToken(tokenMac1)
          keptToken <- env.sessions.findToken(tokenMac2)
          after <- Clock.instant
        yield assertTrue(
          forgotten,
          agents.map(_.id) == List(userAgentId2),
          sessions.map(_.record.publicId) == List(PublicSessionId("kept")),
          forgottenToken.forall(!_.expiresAt.isAfter(after)),
          keptToken.exists(_.expiresAt.isAfter(after)),
        )
      },
      test("forget also expires the refresh tokens of a session that already ended") {
        for
          now <- Clock.instant
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.sessions.create(sessionMac1, session(userId1, userAgentId1, "ended"), 1.hour, None, None)
          _ <- env.sessions.createRefreshToken(tokenMac1, None, refreshToken(userId1, sessionMac1, "ended", now, "family-ended"), None)
          _ <- env.sessions.invalidate(sessionMac1)
          _ <- env.repository.forget(userAgentId1, userId1)
          token <- env.sessions.findToken(tokenMac1)
          after <- Clock.instant
        yield assertTrue(token.forall(!_.expiresAt.isAfter(after)))
      },
      test("forget refuses a user agent that belongs to another user, deleting and expiring nothing") {
        for
          now <- Clock.instant
          _ <- env.repository.create(userAgentId1, data1, ttl)
          _ <- env.sessions.create(sessionMac1, session(userId1, userAgentId1, "own"), 1.hour, None, None)
          _ <- env.sessions.createRefreshToken(tokenMac1, None, refreshToken(userId1, sessionMac1, "own", now, "family-own"), None)
          forgotten <- env.repository.forget(userAgentId1, userId2)
          agents <- env.repository.findByUserId(userId1)
          sessions <- env.sessions.findByUserId(userId1)
          token <- env.sessions.findToken(tokenMac1)
          after <- Clock.instant
        yield assertTrue(
          !forgotten,
          agents.map(_.id) == List(userAgentId1),
          sessions.size == 1,
          token.exists(_.expiresAt.isAfter(after)),
        )
      },
      test("forget returns false for an unknown user agent") {
        for forgotten <- env.repository.forget(userAgentId1, userId1)
        yield assertTrue(!forgotten)
      },
    )

object UserAgentRepositorySpec:
  case class Env(repository: UserAgentRepository, sessions: SessionRepository)
