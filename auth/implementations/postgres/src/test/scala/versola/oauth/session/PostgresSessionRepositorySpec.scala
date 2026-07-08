package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.client.model.{AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.model.AccessToken
import versola.oauth.session.model.RefreshTokenRecord
import versola.util.MAC
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*


object PostgresSessionRepositorySpec extends PostgresSpec, SessionRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield SessionRepositorySpec.Env(PostgresSessionRepository(xa))

  override def beforeEach(env: SessionRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE sso_sessions".update.run())
      _ <- xa.connect(sql"TRUNCATE TABLE refresh_tokens".update.run())
    yield ()

  private val atomicSessionId = MAC(Array.fill(32)(77.toByte))
  private val atomicTokenId   = MAC(Array.fill(32)(78.toByte))

  private def cleanup =
    for
      xa <- ZIO.service[TransactorZIO]
      _  <- xa.connect(sql"TRUNCATE TABLE sso_sessions".update.run())
      _  <- xa.connect(sql"TRUNCATE TABLE refresh_tokens".update.run())
    yield ()

  override def additionalSuites: List[Spec[TransactorZIO & TestEnvironment & Scope, Any]] =
    List(
      suite("atomic invalidation")(
        test("invalidateByUserId atomically removes sessions and refresh tokens") {
          for
            xa      <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            tokens   = PostgresRefreshTokenRepository(xa)
            now     <- Clock.instant
            record   = RefreshTokenRecord(
              sessionId            = atomicSessionId,
              accessToken          = AccessToken(Array.fill(16)(1.toByte)),
              userId               = userId1,
              clientId             = clientId1,
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
            _            <- sessions.create(atomicSessionId, session1, 5.minutes, None)
            _            <- tokens.create(atomicTokenId, record)
            _            <- sessions.invalidateByUserId(userId1)
            sessionAfter <- sessions.find(atomicSessionId)
            tokenAfter   <- tokens.find(atomicTokenId)
          yield assertTrue(sessionAfter.isEmpty, tokenAfter.isEmpty)
        }
      ) @@ TestAspect.before(cleanup) @@ TestAspect.sequential @@ TestAspect.timed
    )
