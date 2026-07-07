package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.client.model.{AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.model.AccessToken
import versola.oauth.session.model.{RefreshTokenRecord, SessionRecord, UserAgentInfo}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Integration test for the atomic behaviour of [[SessionRepository.invalidateByUserId]]:
 *  a single transaction must expire all sessions AND delete all refresh tokens. */
object PostgresSessionAtomicSpec extends PostgresSpec:

  private val sessionId = MAC(Array.fill(32)(1.toByte))
  private val tokenId   = MAC(Array.fill(32)(77.toByte))
  private val userId    = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val clientId  = ClientId("test-client")

  private val session = SessionRecord(
    userId    = userId,
    clientId  = clientId,
    userAgent = UserAgentInfo("desktop", None, None, None),
    createdAt = Instant.EPOCH,
    amr       = Map.empty,
  )

  private def cleanup =
    for
      xa <- ZIO.service[TransactorZIO]
      _  <- xa.connect(sql"TRUNCATE TABLE sso_sessions".update.run())
      _  <- xa.connect(sql"TRUNCATE TABLE refresh_tokens".update.run())
    yield ()

  def spec = suite("PostgresSessionAtomicSpec")(
    test("invalidateByUserId atomically removes sessions and refresh tokens") {
      for
        xa      <- ZIO.service[TransactorZIO]
        sessions = PostgresSessionRepository(xa)
        tokens   = PostgresRefreshTokenRepository(xa)
        now     <- Clock.instant
        record   = RefreshTokenRecord(
          sessionId            = sessionId,
          accessToken          = AccessToken(Array.fill(16)(1.toByte)),
          userId               = userId,
          clientId             = clientId,
          externalAudience     = List.empty,
          scope                = Set(ScopeToken("read")),
          issuedAt             = now,
          expiresAt            = now.plusSeconds(30.days.toSeconds),
          requestedClaims      = None,
          uiLocales            = None,
          nonce                = None,
          previousRefreshToken = None,
          amr                  = Set(AuthMethodRef.pwd),
          authTime             = now,
        )
        _             <- sessions.create(sessionId, session, 5.minutes, None)
        _             <- tokens.create(tokenId, record)
        _             <- sessions.invalidateByUserId(userId)
        sessionAfter  <- sessions.find(sessionId)
        tokenAfter    <- tokens.find(tokenId)
      yield assertTrue(
        sessionAfter.isEmpty,
        tokenAfter.isEmpty,
      )
    }
  ) @@ TestAspect.before(cleanup) @@ TestAspect.sequential @@ TestAspect.timed
