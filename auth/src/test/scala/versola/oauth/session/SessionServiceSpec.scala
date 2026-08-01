package versola.oauth.session

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionId, SessionInfo, SessionRecord, UserAgentInfo}
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

  private val record = SessionRecord(
    userId = userId,
    clients = List(ClientEntry(clientId, Instant.EPOCH)),
    userAgent = UserAgentInfo("desktop", None, None, None),
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = publicId,
  )

  class Env:
    val repository = stub[SessionRepository]
    val security = stub[SecurityService]
    val service: SessionService = SessionService.Impl(repository, security, TestEnvConfig.coreConfig)

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
    ),
  )
