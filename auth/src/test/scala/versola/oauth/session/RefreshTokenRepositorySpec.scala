package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.RefreshToken
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{SessionId, TokenCreationRecord, TokenRecord}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.prelude.EqualOps
import zio.test.*

import java.time.Instant
import java.util.UUID

trait RefreshTokenRepositorySpec extends DatabaseSpecBase[RefreshTokenRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val sessionId2 = MAC(Array.fill(32)(2.toByte))

  val refreshToken1 = MAC(Array.fill(32)(20.toByte))
  val refreshToken2 = MAC(Array.fill(32)(21.toByte))
  val refreshToken3 = MAC(Array.fill(32)(22.toByte))

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  val scope2 = Set(ScopeToken("admin"))

  val refreshTtl = 30.days

  def creationRecord1(now: Instant) = TokenCreationRecord(
    sessionId = sessionId1,
    userId = userId1,
    clientId = clientId1,
    scope = scope1,
    issuedAt = now,
  )

  def creationRecord2(now: Instant) = TokenCreationRecord(
    sessionId = sessionId2,
    userId = userId2,
    clientId = clientId2,
    scope = scope2,
    issuedAt = now,
  )

  def expectedTokenRecord(creation: TokenCreationRecord, ttl: Duration): TokenRecord =
    TokenRecord(
      sessionId = creation.sessionId,
      userId = creation.userId,
      clientId = creation.clientId,
      scope = creation.scope,
      issuedAt = creation.issuedAt,
      expiresAt = creation.issuedAt.plusSeconds(ttl.toSeconds),
    )

  def testCases(env: RefreshTokenRepositorySpec.Env): List[Spec[RefreshTokenRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find multiple refresh tokens") {
        for
          now <- Clock.instant
          creation1 = creationRecord1(now)
          creation2 = creationRecord2(now)
          _ <- env.repository.create(refreshToken1, refreshTtl, creation1)
          _ <- env.repository.create(refreshToken2, refreshTtl, creation2)
          found1 <- env.repository.findRefreshToken(refreshToken1)
          found2 <- env.repository.findRefreshToken(refreshToken2)
        yield assertTrue(
          found1.isDefined,
          found2.isDefined,
        )
      },
      test("find returns None for non-existent refresh token") {
        for
          found <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(found.isEmpty)
      },
      test("refresh token expires after TTL") {
        val shortTtl = 2.minutes
        for
          now <- Clock.instant
          creation = creationRecord1(now)
          expectedRecord = expectedTokenRecord(creation, shortTtl)
          _ <- env.repository.create(refreshToken1, shortTtl, creation)
          foundBefore <- env.repository.findRefreshToken(refreshToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundBefore.exists(_ === expectedRecord),
          foundAfter.isEmpty,
        )
      },
    )




object RefreshTokenRepositorySpec:
  case class Env(repository: RefreshTokenRepository)

