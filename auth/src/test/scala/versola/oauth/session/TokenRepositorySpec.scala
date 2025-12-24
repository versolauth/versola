package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{SessionId, TokenRecord, WithTtl}
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

  val now = Instant.now()

  val record1 = TokenRecord(
    sessionId = sessionId1,
    userId = userId1,
    clientId = clientId1,
    scope = scope1,
    issuedAt = now,
    expiresAt = now.plusSeconds(accessTtl.toSeconds),
  )

  val record2 = TokenRecord(
    sessionId = sessionId2,
    userId = userId2,
    clientId = clientId2,
    scope = scope2,
    issuedAt = now,
    expiresAt = now.plusSeconds(accessTtl.toSeconds),
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
          _ <- env.repository.create(tokens, record1)
          foundAccess <- env.repository.findAccessToken(accessToken1)
          foundRefresh <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundAccess.exists(_ === record1),
          foundRefresh.exists(_ === record1),
        )
      },
      test("create with only access token") {
        val tokens = These.Left(WithTtl(accessToken1, accessTtl))
        for
          _ <- env.repository.create(tokens, record1)
          foundAccess <- env.repository.findAccessToken(accessToken1)
          foundRefresh <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundAccess.exists(_ === record1),
          foundRefresh.isEmpty,
        )
      },
      test("create with only refresh token") {
        val tokens = These.Right(WithTtl(refreshToken1, refreshTtl))
        for
          _ <- env.repository.create(tokens, record1)
          foundAccess <- env.repository.findAccessToken(accessToken1)
          foundRefresh <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundAccess.isEmpty,
          foundRefresh.exists(_ === record1),
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
          _ <- env.repository.create(tokens1, record1)
          _ <- env.repository.create(tokens2, record2)
          found1 <- env.repository.findAccessToken(accessToken1)
          found2 <- env.repository.findAccessToken(accessToken2)
        yield assertTrue(
          found1.exists(_ === record1),
          found2.exists(_ === record2),
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
          _ <- env.repository.create(tokens1, record1)
          _ <- env.repository.create(tokens2, record2)
          found1 <- env.repository.findRefreshToken(refreshToken1)
          found2 <- env.repository.findRefreshToken(refreshToken2)
        yield assertTrue(
          found1.exists(_ === record1),
          found2.exists(_ === record2),
        )
      },
    )

  def expirationTests(env: TokenRepositorySpec.Env) =
    suite("expiration")(
      test("access token expires after TTL") {
        val tokens = These.Left(WithTtl(accessToken1, 2.minutes))
        for
          _ <- env.repository.create(tokens, record1)
          foundBefore <- env.repository.findAccessToken(accessToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findAccessToken(accessToken1)
        yield assertTrue(
          foundBefore.exists(_ === record1),
          foundAfter.isEmpty,
        )
      },
      test("refresh token expires after TTL") {
        val tokens = These.Right(WithTtl(refreshToken1, 2.minutes))
        for
          _ <- env.repository.create(tokens, record1)
          foundBefore <- env.repository.findRefreshToken(refreshToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findRefreshToken(refreshToken1)
        yield assertTrue(
          foundBefore.exists(_ === record1),
          foundAfter.isEmpty,
        )
      },
    )

object TokenRepositorySpec:
  case class Env(repository: TokenRepository)

