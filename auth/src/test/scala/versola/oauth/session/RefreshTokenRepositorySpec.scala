package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{Acr, AuthMethodRef, AuthorizationDetail, ClientId, ScopeToken}
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{PublicSessionId, RefreshTokenRecord, SessionId}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.prelude.EqualOps
import zio.test.*

import java.time.Instant
import java.util.UUID

trait RefreshTokenRepositorySpec extends DatabaseSpecBase[RefreshTokenRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val sessionId2 = MAC(Array.fill(32)(2.toByte))

  val publicSessionId1 = PublicSessionId("public-session-1")
  val publicSessionId2 = PublicSessionId("public-session-2")

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

  val accessToken1 = AccessToken(Array.fill(16)(10.toByte))
  val accessToken2 = AccessToken(Array.fill(16)(11.toByte))
  val accessToken3 = AccessToken(Array.fill(16)(12.toByte))

  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  val scope2 = Set(ScopeToken("admin"))

  val refreshTtl = 30.days

  def tokenRecord1(now: Instant, ttl: Duration) = RefreshTokenRecord(
    sessionId = sessionId1,
    publicSessionId = publicSessionId1,
    accessToken = accessToken1,
    userId = userId1,
    clientId = clientId1,
    audience = List.empty,
    authorizationDetails = None,
    scope = scope1,
    issuedAt = now,
    expiresAt = now.plusSeconds(ttl.toSeconds),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    amr = Set(AuthMethodRef.pwd),
    authTime = now,
    acr = None,
  )

  def tokenRecord2(now: Instant, ttl: Duration) = RefreshTokenRecord(
    sessionId = sessionId2,
    publicSessionId = publicSessionId2,
    accessToken = accessToken2,
    userId = userId2,
    clientId = clientId2,
    audience = List.empty,
    authorizationDetails = None,
    scope = scope2,
    issuedAt = now,
    expiresAt = now.plusSeconds(ttl.toSeconds),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    amr = Set(AuthMethodRef.pwd),
    authTime = now,
    acr = None,
  )

  def testCases(env: RefreshTokenRepositorySpec.Env): List[Spec[RefreshTokenRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find multiple refresh tokens") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          record2 = tokenRecord2(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, None, record2)
          found1 <- env.repository.findToken(refreshToken1)
          found2 <- env.repository.findToken(refreshToken2)
        yield assertTrue(
          found1.isDefined,
          found2.isDefined,
        )
      },
      test("persist and retrieve authorization details verbatim") {
        val detail = AuthorizationDetail.parse(
          """{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"}}"""
            .fromJson[Json].toOption.get,
        ).toOption.get
        for
          now <- Clock.instant
          record = tokenRecord1(now, refreshTtl).copy(authorizationDetails = Some(List(detail)))
          _ <- env.repository.createRefreshToken(refreshToken1, None, record)
          found <- env.repository.findToken(refreshToken1)
        yield assertTrue(found.map(_.authorizationDetails) == Some(Some(List(detail))))
      },
      test("find returns None for non-existent refresh token") {
        for
          found <- env.repository.findToken(refreshToken1)
        yield assertTrue(found.isEmpty)
      },
      test("refresh token expires after TTL") {
        val shortTtl = 2.minutes
        for
          now <- Clock.instant
          record = tokenRecord1(now, shortTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record)
          foundBefore <- env.repository.findToken(refreshToken1)
          _ <- TestClock.adjust(3.minutes)
          foundAfter <- env.repository.findToken(refreshToken1)
        yield assertTrue(
          foundBefore.exists(_ === record),
          foundAfter.isEmpty,
        )
      },
      test("refresh token rotation: old token deleted, new token created") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))
          oldTokenFound <- env.repository.findToken(refreshToken1)
          newTokenFound <- env.repository.findToken(refreshToken2)
        yield assertTrue(
          // Retired rather than deleted: the row stays behind so a later replay of it still
          // resolves to its family, but it is no longer usable.
          oldTokenFound.isEmpty,
          newTokenFound.isDefined,
        )
      },
      test("refresh token rotation: fail when old token already used") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)

          refreshTokens = List(
            refreshToken2,
            refreshToken3,
            refreshToken4,
            refreshToken5,
            refreshToken6,
            refreshToken7,
          )
          results <- ZIO.foreachPar(refreshTokens.zipWithIndex)((token, i) =>
            env.repository
              .createRefreshToken(token, Some(refreshToken1), record1.copy(accessToken = AccessToken(Array.fill(16)((30 + i).toByte))))
              .either,
          )

        yield assertTrue(
          results.count(_.isRight) == 1,
          results.count(_.left.toOption.contains(())) == 5
        )
      },
      test("revokeFamily revokes the whole family when a token retired generations ago is replayed") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))
          _ <- env.repository.createRefreshToken(refreshToken3, Some(refreshToken2), record1.copy(accessToken = accessToken3))

          // The replayed token was retired two rotations ago, so a lookup that only knew the
          // immediately following generation would have missed it and left the tip live.
          revoked <- env.repository.revokeFamily(refreshToken1, clientId1, now.minusSeconds(300))
          tipAfter <- env.repository.findToken(refreshToken3)
        yield assertTrue(
          revoked.exists(_.userId == userId1),
          // Array-backed AccessToken has reference equality under `==`, hence `===`.
          revoked.exists(_.accessTokens.exists(_ === accessToken3)),
          // Expired, not deleted: the cleanup manager's expires_at sweep collects the family
          // later, rather than this call issuing its own DELETE inline.
          tipAfter.isEmpty,
        )
      },
      test("revokeFamily ignores a token that was never issued") {
        for
          now <- Clock.instant
          _ <- env.repository.createRefreshToken(refreshToken1, None, tokenRecord1(now, refreshTtl))

          revoked <- env.repository.revokeFamily(refreshToken2, clientId1, now.minusSeconds(300))
        yield assertTrue(revoked.isEmpty)
      },
      test("revokeFamily ignores a token that is still live") {
        for
          now <- Clock.instant
          _ <- env.repository.createRefreshToken(refreshToken1, None, tokenRecord1(now, refreshTtl))

          // Presenting a token that was never rotated away is not a replay, whatever else is
          // wrong with the request.
          revoked <- env.repository.revokeFamily(refreshToken1, clientId1, now.minusSeconds(300))
          stillLive <- env.repository.findToken(refreshToken1)
        yield assertTrue(
          revoked.isEmpty,
          stillLive.isDefined,
        )
      },
      test("revokeFamily does not act on a family owned by a different client") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))

          revoked <- env.repository.revokeFamily(refreshToken1, clientId2, now.minusSeconds(300))
          tipAfter <- env.repository.findToken(refreshToken2)
        yield assertTrue(
          revoked.isEmpty,
          tipAfter.isDefined,
        )
      },
      test("revokeFamily leaves out access tokens already past their TTL") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))

          revoked <- env.repository.revokeFamily(refreshToken1, clientId1, now.plusSeconds(60))
          tipAfter <- env.repository.findToken(refreshToken2)
        yield assertTrue(
          // The family still dies; there is just nothing left worth pushing to the client.
          revoked.exists(_.accessTokens.isEmpty),
          tipAfter.isEmpty,
        )
      },
      test("rotation of a family that was already revoked fails") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))
          _ <- env.repository.revokeFamily(refreshToken1, clientId1, now.minusSeconds(300))

          rotated <- env.repository.createRefreshToken(refreshToken3, Some(refreshToken2), record1.copy(accessToken = accessToken3)).either
        yield assertTrue(rotated.isLeft)
      },
      test("a rotation racing a revocation never leaves a live successor behind") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))

          // Whichever wins the family lock, the invariant holds: either the rotation commits
          // first and its successor is expired by the revocation, or it finds the token it
          // meant to rotate already dead.
          _ <- env.repository.createRefreshToken(refreshToken3, Some(refreshToken2), record1.copy(accessToken = accessToken3)).either
            .zipPar(env.repository.revokeFamily(refreshToken1, clientId1, now.minusSeconds(300)))

          live <- ZIO.foreach(List(refreshToken1, refreshToken2, refreshToken3))(env.repository.findToken)
        yield assertTrue(live.forall(_.isEmpty))
      },
      test("revokeFamily is idempotent: a second replay finds the family already dead") {
        for
          now <- Clock.instant
          record1 = tokenRecord1(now, refreshTtl)
          _ <- env.repository.createRefreshToken(refreshToken1, None, record1)
          _ <- env.repository.createRefreshToken(refreshToken2, Some(refreshToken1), record1.copy(accessToken = accessToken2))

          _ <- env.repository.revokeFamily(refreshToken1, clientId1, now.minusSeconds(300))
          second <- env.repository.revokeFamily(refreshToken1, clientId1, now.minusSeconds(300))
        yield assertTrue(second.exists(_.accessTokens.isEmpty))
      },
    )

object RefreshTokenRepositorySpec:
  case class Env(repository: SessionRepository)
