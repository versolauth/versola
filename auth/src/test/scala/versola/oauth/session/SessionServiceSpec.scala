package versola.oauth.session

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionId, SessionInfo, SessionRecord, SessionUnderUserAgent, UserAgentDetails, UserAgentId}
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
          _      <- env.security.mac.succeedsWith(mac)
          _      <- env.repository.invalidate.succeedsWith(Some(record))
          result <- env.service.invalidate(Right(rawId))
        yield assertTrue(result.contains(SessionInfo(mac, record)))
      },
      test("Right(rawId) returns None when the repository finds nothing") {
        val env = Env()
        for
          _      <- env.security.mac.succeedsWith(mac)
          _      <- env.repository.invalidate.succeedsWith(None)
          result <- env.service.invalidate(Right(rawId))
        yield assertTrue(result.isEmpty)
      },
      test("Left(publicId) delegates to repository.invalidateByPublicId") {
        val env = Env()
        for
          _      <- env.repository.invalidateByPublicId.succeedsWith(Some((mac, record)))
          result <- env.service.invalidate(Left(publicId))
        yield assertTrue(result.contains(SessionInfo(mac, record)))
      },
      test("Left(publicId) returns None when the repository finds nothing") {
        val env = Env()
        for
          _      <- env.repository.invalidateByPublicId.succeedsWith(None)
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
            _      <- env.repository.invalidateByPublicIdForUser.succeedsWith(true)
            result <- env.service.invalidateForUser(publicId, userId)
          yield assertTrue(
            result,
            env.repository.invalidateByPublicIdForUser.calls == List((publicId, userId)),
          )
        },
    ),
    suite("listByUser")(
      test("produces one entry per session, enriched with its user agent's details") {
        val env = Env()
        val userAgentId1 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val userAgentId2 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
        val details1 = UserAgentDetails(Some("desktop"), Some("macOS 14"), Some("Chrome"), Some("120"))
        val details2 = UserAgentDetails(Some("ios"), Some("iOS 17"), Some("Safari"), Some("17"))
        val session1 = record.copy(userAgentId = userAgentId1, publicId = PublicSessionId("s1"))
        val session2 = record.copy(userAgentId = userAgentId2, publicId = PublicSessionId("s2"))
        for
          _      <- env.repository.findByUserId.succeedsWith(List(session1, session2))
          _      <- env.userAgentRepository.findMany.succeedsWith(Map(userAgentId1 -> details1, userAgentId2 -> details2))
          result <- env.service.listByUser(userId)
        yield assertTrue(
          result == List(
            SessionUnderUserAgent(session1.publicId, session1.clients, session1.createdAt, details1.platform, details1.os, details1.browser, details1.version, session1.expiresAt),
            SessionUnderUserAgent(session2.publicId, session2.clients, session2.createdAt, details2.platform, details2.os, details2.browser, details2.version, session2.expiresAt),
          ),
          env.userAgentRepository.findMany.calls == List(List(userAgentId1, userAgentId2)),
        )
      },
      test("falls back to unknown details when the user agent has expired") {
        val env = Env()
        val userAgentId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val session = record.copy(userAgentId = userAgentId)
        val unknown = UserAgentDetails.parse(None)
        for
          _      <- env.repository.findByUserId.succeedsWith(List(session))
          _      <- env.userAgentRepository.findMany.succeedsWith(Map.empty)
          result <- env.service.listByUser(userId)
        yield assertTrue(
          result == List(SessionUnderUserAgent(session.publicId, session.clients, session.createdAt, unknown.platform, unknown.os, unknown.browser, unknown.version, session.expiresAt)),
        )
      },
    ),
  )
