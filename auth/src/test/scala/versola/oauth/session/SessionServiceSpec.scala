package versola.oauth.session

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{
  ClientEntry,
  EndedSession,
  PublicSessionId,
  SessionId,
  SessionInfo,
  SessionRecord,
  SessionUnderUserAgent,
  StoredSession,
  UserAgentDetails,
  UserAgentId,
  UserAgentRecord,
}
import versola.user.model.UserId
import versola.util.{MAC, SecurityService, UnitSpecBase}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object SessionServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val clientId = ClientId("client-1")
  private val publicId = PublicSessionId("public-session-1")
  private val rawId = SessionId(Array.fill(32)(9.toByte))
  private val mac = MAC(Array.fill(32)(1.toByte))

  private val defaultUserAgentId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-000000000000"))

  private val record = SessionRecord(
    userId = userId,
    clients = List(ClientEntry(clientId, Instant.EPOCH)),
    userAgentId = defaultUserAgentId,
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = publicId,
    expiresAt = Instant.EPOCH,
  )

  private val userAgentId1 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
  private val userAgentId2 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
  private val userAgentId3 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a3"))

  private def userAgent(id: UserAgentId, details: UserAgentDetails) =
    UserAgentRecord(
      id = id,
      userAgent = Some(s"raw-$id"),
      platform = details.platform,
      os = details.os,
      browser = details.browser,
      version = details.version,
      createdAt = Instant.EPOCH,
      expiresAt = Instant.ofEpochSecond(2000),
    )

  class Env:
    val repository = stub[SessionRepository]
    val security = stub[SecurityService]
    val userAgentRepository = stub[UserAgentRepository]
    val service: SessionService = SessionService.Impl(repository, security, TestEnvConfig.coreConfig, userAgentRepository)

  def spec = suite("SessionService")(
    suite("invalidate")(
      test("Right(rawId) computes the MAC and delegates to repository.invalidate") {
        val env = Env()
        for
          _ <- env.security.mac.succeedsWith(mac)
          _ <- env.repository.invalidate.succeedsWith(Some(record))
          result <- env.service.invalidate(Right(rawId))
        yield assertTrue(result.contains(SessionInfo(mac, record)))
      },
      test("Right(rawId) returns None when the repository finds nothing") {
        val env = Env()
        for
          _ <- env.security.mac.succeedsWith(mac)
          _ <- env.repository.invalidate.succeedsWith(None)
          result <- env.service.invalidate(Right(rawId))
        yield assertTrue(result.isEmpty)
      },
      test("Left(publicId) delegates to repository.invalidateByPublicId") {
        val env = Env()
        for
          _ <- env.repository.invalidateByPublicId.succeedsWith(Some((mac, record)))
          result <- env.service.invalidate(Left(publicId))
        yield assertTrue(result.contains(SessionInfo(mac, record)))
      },
      test("Left(publicId) returns None when the repository finds nothing") {
        val env = Env()
        for
          _ <- env.repository.invalidateByPublicId.succeedsWith(None)
          result <- env.service.invalidate(Left(publicId))
        yield assertTrue(result.isEmpty)
      },
      test("Left(publicId) never invokes SecurityService.mac") {
        val env = Env()
        for
          _ <- env.repository.invalidateByPublicId.succeedsWith(Some((mac, record)))
          _ <- env.service.invalidate(Left(publicId))
        yield assertTrue(env.security.mac.calls.isEmpty)
      },
      test("invalidateForUser delegates the ownership check to the repository") {
        val env = Env()
        for
          _ <- env.repository.invalidateByPublicIdForUser.succeedsWith(true)
          result <- env.service.invalidateForUser(publicId, userId)
        yield assertTrue(
          result,
          env.repository.invalidateByPublicIdForUser.calls == List((publicId, userId)),
        )
      },
    ),
    suite("listByUser")(
      test("produces one entry per session, enriched with its user agent's record and its expiries") {
        val env = Env()
        val agent1 = userAgent(userAgentId1, UserAgentDetails(Some("desktop"), Some("macOS 14"), Some("Chrome"), Some("120")))
        val agent2 = userAgent(userAgentId2, UserAgentDetails(Some("ios"), Some("iOS 17"), Some("Safari"), Some("17")))
        val session1 = record.copy(userAgentId = userAgentId1, publicId = PublicSessionId("s1"), expiresAt = Instant.ofEpochSecond(1000))
        val session2 = record.copy(userAgentId = userAgentId2, publicId = PublicSessionId("s2"))
        val idle = Instant.ofEpochSecond(500)
        for
          _ <- env.repository.findByUserId.succeedsWith(List(StoredSession(session1, Some(idle)), StoredSession(session2, None)))
          _ <- env.userAgentRepository.findByUserId.succeedsWith(List(agent1, agent2))
          result <- env.service.listByUser(userId)
        yield assertTrue(
          result == List(
            SessionUnderUserAgent(
              publicId = session1.publicId,
              clients = session1.clients,
              createdAt = session1.createdAt,
              platform = agent1.platform,
              os = agent1.os,
              browser = agent1.browser,
              version = agent1.version,
              expiresAt = Instant.ofEpochSecond(1000),
              idleExpiresAt = Some(idle),
              userAgent = Some(agent1),
            ),
            SessionUnderUserAgent(
              publicId = session2.publicId,
              clients = session2.clients,
              createdAt = session2.createdAt,
              platform = agent2.platform,
              os = agent2.os,
              browser = agent2.browser,
              version = agent2.version,
              expiresAt = session2.expiresAt,
              idleExpiresAt = None,
              userAgent = Some(agent2),
            ),
          ),
          env.userAgentRepository.findByUserId.calls == List(userId),
        )
      },
      test("falls back to unknown details and no user agent record when the user agent has expired") {
        val env = Env()
        val session = record.copy(userAgentId = userAgentId1)
        val unknown = UserAgentDetails.parse(None)
        for
          _ <- env.repository.findByUserId.succeedsWith(List(StoredSession(session, None)))
          _ <- env.userAgentRepository.findByUserId.succeedsWith(Nil)
          result <- env.service.listByUser(userId)
        yield assertTrue(
          result == List(SessionUnderUserAgent(
            publicId = session.publicId,
            clients = session.clients,
            createdAt = session.createdAt,
            platform = unknown.platform,
            os = unknown.os,
            browser = unknown.browser,
            version = unknown.version,
            expiresAt = session.expiresAt,
            idleExpiresAt = None,
            userAgent = None,
          )),
        )
      },
    ),
    suite("listDevicesByUser")(
      test("pairs a signed-in user agent with its session and lists the others as signed out with their ended sessions") {
        val env = Env()
        val signedIn = userAgent(userAgentId1, UserAgentDetails(Some("desktop"), Some("macOS 14"), Some("Chrome"), Some("120")))
        val signedOut = userAgent(userAgentId2, UserAgentDetails(Some("ios"), Some("iOS 17"), Some("Safari"), Some("17")))
        val neverSignedIn = userAgent(userAgentId3, UserAgentDetails(None, None, None, None))
        val live = record.copy(userAgentId = userAgentId1, publicId = PublicSessionId("live"))
        val ended = List(
          EndedSession(PublicSessionId("old-1"), userAgentId2),
          EndedSession(PublicSessionId("old-2"), userAgentId2),
          EndedSession(PublicSessionId("old-of-signed-in"), userAgentId1),
        )
        for
          _ <- env.repository.findByUserId.succeedsWith(List(StoredSession(live, None)))
          _ <- env.userAgentRepository.findByUserId.succeedsWith(List(signedIn, signedOut, neverSignedIn))
          _ <- env.repository.findEndedByUserId.succeedsWith(ended)
          devices <- env.service.listDevicesByUser(userId)
        yield assertTrue(
          devices.map(_.userAgent.map(_.id)) == List(Some(userAgentId1), Some(userAgentId2), Some(userAgentId3)),
          devices.map(_.session.map(_.publicId)) == List(Some(live.publicId), None, None),
          devices.map(_.endedSessionIds) == List(Nil, List(PublicSessionId("old-1"), PublicSessionId("old-2")), Nil),
          devices.head.session.flatMap(_.userAgent).contains(signedIn),
        )
      },
      test("lists a live session whose user agent row is gone with no user agent") {
        val env = Env()
        val live = record.copy(userAgentId = userAgentId1)
        for
          _ <- env.repository.findByUserId.succeedsWith(List(StoredSession(live, None)))
          _ <- env.userAgentRepository.findByUserId.succeedsWith(Nil)
          _ <- env.repository.findEndedByUserId.succeedsWith(Nil)
          devices <- env.service.listDevicesByUser(userId)
        yield assertTrue(devices.map(d => (d.userAgent, d.session.map(_.publicId))) == List((None, Some(live.publicId))))
      },
      test("lists nothing for a user with no user agents and no sessions") {
        val env = Env()
        for
          _ <- env.repository.findByUserId.succeedsWith(Nil)
          _ <- env.userAgentRepository.findByUserId.succeedsWith(Nil)
          _ <- env.repository.findEndedByUserId.succeedsWith(Nil)
          devices <- env.service.listDevicesByUser(userId)
        yield assertTrue(devices.isEmpty)
      },
    ),
    suite("forgetUserAgent")(
      test("delegates to the user agent repository with the user it must belong to") {
        val env = Env()
        for
          _ <- env.userAgentRepository.forget.succeedsWith(true)
          forgotten <- env.service.forgetUserAgent(userId, userAgentId1)
        yield assertTrue(forgotten, env.userAgentRepository.forget.calls == List((userAgentId1, userId)))
      },
      test("reports false when the repository does not know the user agent as the user's") {
        val env = Env()
        for
          _ <- env.userAgentRepository.forget.succeedsWith(false)
          forgotten <- env.service.forgetUserAgent(userId, userAgentId1)
        yield assertTrue(!forgotten)
      },
    ),
  )
