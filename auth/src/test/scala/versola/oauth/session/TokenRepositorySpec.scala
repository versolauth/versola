package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{SessionId, TokenCreationRecord, TokenRecord, WithTtl}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.prelude.{EqualOps, These}
import zio.test.*

import java.time.Instant
import java.util.UUID

trait TokenRepositorySpec extends DatabaseSpecBase[TokenRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val sessionId2 = MAC(Array.fill(32)(2.toByte))

  val accessToken1 = MAC(Array.fill(32)(10.toByte))
  val accessToken2 = MAC(Array.fill(32)(11.toByte))
  val accessToken3 = MAC(Array.fill(32)(12.toByte))

  val refreshToken1 = MAC(Array.fill(32)(20.toByte))
  val refreshToken2 = MAC(Array.fill(32)(21.toByte))
  val refreshToken3 = MAC(Array.fill(32)(22.toByte))

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  val scope2 = Set(ScopeToken("admin"))

  val accessTtl = 15.minutes
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

  def testCases(env: TokenRepositorySpec.Env): List[Spec[TokenRepositorySpec.Env & Scope, Any]] =
    List(
      createTests(env),
      findAccessTokenTests(env),
      findRefreshTokenTests(env),
      expirationTests(env),
    )

  def createTests(env: TokenRepositorySpec.Env) =
    suite("create")(
      test("create with both access and refresh tokens") {
        val tokens = These.Both(
          WithTtl(accessToken1, accessTtl),
          WithTtl(refreshToken1, refreshTtl),
        )
        for
          now <- Clock.instant
          creation = creationRecord1(now)
          expectedAccessRecord = expectedTokenRecord(creation, accessTtl)
          expectedRefreshRecord = expectedTokenRecord(creation, refreshTtl)
          _ <- env.repository.create(tokens, creation)
          foundAccess <- env.repository.findAccessToken(accessToken1)
          foundRefresh <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundAccess.exists(_ === expectedAccessRecord),
          foundRefresh.exists(_ === expectedRefreshRecord),
        )
      },
      test("create with only access token") {
        val tokens = These.Left(WithTtl(accessToken1, accessTtl))
        for
          now <- Clock.instant
          creation = creationRecord1(now)
          expectedRecord = expectedTokenRecord(creation, accessTtl)
          _ <- env.repository.create(tokens, creation)
          foundAccess <- env.repository.findAccessToken(accessToken1)
          foundRefresh <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundAccess.exists(_ === expectedRecord),
          foundRefresh.isEmpty,
        )
      },
      test("create with only refresh token") {
        val tokens = These.Right(WithTtl(refreshToken1, refreshTtl))
        for
          now <- Clock.instant
          creation = creationRecord1(now)
          expectedRefreshRecord = expectedTokenRecord(creation, refreshTtl)
          _ <- env.repository.create(tokens, creation)
          foundAccess <- env.repository.findAccessToken(accessToken1)
          foundRefresh <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundAccess.isEmpty,
          foundRefresh.exists(_ === expectedRefreshRecord),
        )
      },
    )

  def findAccessTokenTests(env: TokenRepositorySpec.Env) =
    suite("findAccessToken")(
      test("find returns None for non-existent access token") {
        for
          found <- env.repository.findAccessToken(accessToken1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns correct record for existing access token") {
        val tokens1 = These.Left(WithTtl(accessToken1, accessTtl))
        val tokens2 = These.Left(WithTtl(accessToken2, accessTtl))
        for
          now <- Clock.instant
          creation1 = creationRecord1(now)
          creation2 = creationRecord2(now)
          expected1 = expectedTokenRecord(creation1, accessTtl)
          expected2 = expectedTokenRecord(creation2, accessTtl)
          _ <- env.repository.create(tokens1, creation1)
          _ <- env.repository.create(tokens2, creation2)
          found1 <- env.repository.findAccessToken(accessToken1)
          found2 <- env.repository.findAccessToken(accessToken2)
        yield assertTrue(
          found1.exists(_ === expected1),
          found2.exists(_ === expected2),
        )
      },
    )

  def findRefreshTokenTests(env: TokenRepositorySpec.Env) =
    suite("findRefreshToken")(
      test("find returns None for non-existent refresh token") {
        for
          found <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns correct record for existing refresh token") {
        val tokens1 = These.Right(WithTtl(refreshToken1, refreshTtl))
        val tokens2 = These.Right(WithTtl(refreshToken2, refreshTtl))
        for
          now <- Clock.instant
          creation1 = creationRecord1(now)
          creation2 = creationRecord2(now)
          expected1 = expectedTokenRecord(creation1, refreshTtl)
          expected2 = expectedTokenRecord(creation2, refreshTtl)
          _ <- env.repository.create(tokens1, creation1)
          _ <- env.repository.create(tokens2, creation2)
          found1 <- env.repository.findRefreshToken(refreshToken1)
          found2 <- env.repository.findRefreshToken(refreshToken2)
        yield assertTrue(
          found1.exists(_ === expected1),
          found2.exists(_ === expected2),
        )
      },
    )

  def expirationTests(env: TokenRepositorySpec.Env) =
    suite("expiration")(
      test("access token expires after TTL") {
        val shortTtl = 2.minutes
        val tokens = These.Left(WithTtl(accessToken1, shortTtl))
        for
          now <- Clock.instant
          creation = creationRecord1(now)
          expectedRecord = expectedTokenRecord(creation, shortTtl)
          _ <- env.repository.create(tokens, creation)
          foundBefore <- env.repository.findAccessToken(accessToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findAccessToken(accessToken1)
        yield assertTrue(
          foundBefore.exists(_ === expectedRecord),
          foundAfter.isEmpty,
        )
      },
      test("refresh token expires after TTL") {
        val shortTtl = 2.minutes
        val tokens = These.Right(WithTtl(refreshToken1, shortTtl))
        for
          now <- Clock.instant
          creation = creationRecord1(now)
          expectedRecord = expectedTokenRecord(creation, shortTtl)
          _ <- env.repository.create(tokens, creation)
          foundBefore <- env.repository.findRefreshToken(refreshToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundBefore.exists(_ === expectedRecord),
          foundAfter.isEmpty,
        )
      },
    )

object TokenRepositorySpec:
  case class Env(repository: TokenRepository)

