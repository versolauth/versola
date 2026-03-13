package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.RefreshToken
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{RefreshTokenRecord, SessionId}
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
  val refreshToken4 = MAC(Array.fill(32)(23.toByte))
  val refreshToken5 = MAC(Array.fill(32)(24.toByte))
  val refreshToken6 = MAC(Array.fill(32)(25.toByte))
  val refreshToken7 = MAC(Array.fill(32)(26.toByte))

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  val scope2 = Set(ScopeToken("admin"))

  val refreshTtl = 30.days

  def tokenRecord1(now: Instant, ttl: Duration) = RefreshTokenRecord(
    sessionId = sessionId1,
    userId = userId1,
    clientId = clientId1,
    scope = scope1,
    issuedAt = now,
    expiresAt = now.plusSeconds(ttl.toSeconds),
    requestedClaims = None,
    uiLocales = None,
    previousRefreshToken = None,
  )

  def tokenRecord2(now: Instant, ttl: Duration) = RefreshTokenRecord(
    sessionId = sessionId2,
    userId = userId2,
    clientId = clientId2,
    scope = scope2,
    issuedAt = now,
    expiresAt = now.plusSeconds(ttl.toSeconds),
    requestedClaims = None,
    uiLocales = None,
    previousRefreshToken = None,
  )

  def testCases(env: RefreshTokenRepositorySpec.Env): List[Spec[RefreshTokenRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find multiple refresh tokens") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          record2 = tokenRecord2(now, refreshTtl)
          _ <- env.repository.create(refreshToken1, record1)
          _ <- env.repository.create(refreshToken2, record2)
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
          record = tokenRecord1(now, shortTtl)
          _ <- env.repository.create(refreshToken1, record)
          foundBefore <- env.repository.findRefreshToken(refreshToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundBefore.exists(_ === record),
          foundAfter.isEmpty,
        )
      },
      test("refresh token rotation: old token deleted, new token created") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          record2 = record1.copy(previousRefreshToken = Some(refreshToken1))
          _ <- env.repository.create(refreshToken1, record1)
          _ <- env.repository.create(refreshToken2, record2)
          oldTokenFound <- env.repository.findRefreshToken(refreshToken1)
          newTokenFound <- env.repository.findRefreshToken(refreshToken2)
        yield assertTrue(
          oldTokenFound.isEmpty,
          newTokenFound.isDefined,
        )
      },
      test("refresh token rotation: fail when old token already used") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.create(refreshToken1, record1)

          refreshTokens = List(
            refreshToken2,
            refreshToken3,
            refreshToken4,
            refreshToken5,
            refreshToken6,
            refreshToken7,
          )
          results <- ZIO.foreachPar(refreshTokens)(
            env.repository.create(_, record1.copy(previousRefreshToken = Some(refreshToken1))).either,
          )

        yield assertTrue(
          results.count(_.isRight) == 1,
          results.count(_.isLeft) == 5,
        )
      },
    )

object RefreshTokenRepositorySpec:
  case class Env(repository: RefreshTokenRepository)
