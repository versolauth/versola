package versola.oauth.conversation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.PostgresAuthorizationCodeRepository
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.client.model.{Acr, AuthFlow, AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.model.{AccessToken, AuthorizationCode, AuthorizationCodeRecord, CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.PostgresSessionRepository
import versola.oauth.session.model.{ClientEntry, PriorSession, PublicSessionId, RefreshTokenRecord, SessionId, SessionRecord, UserAgentInfo}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.PostgresSpec
import zio.*
import zio.http.URL
import zio.test.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Regression coverage for issue #102: `ConversationFinalizer.finish` must delete the conversation
  * and create the authorization code + session as a single atomic unit — never a state where the
  * conversation is gone but the code/session were never created.
  */
object PostgresConversationFinalizerSpec extends PostgresSpec:

  // Fixed (non-random) UUIDs, same convention as ConversationRepositorySpec: AuthId/UserId derive
  // their embedded "createdAt" from the UUID bits (UUIDv7), so arbitrary UUIDs must be reused
  // consistently rather than generated with UUID.randomUUID().
  private val authId1 = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  private val authId2 = AuthId(UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"))
  private val authId3 = AuthId(UUID.fromString("cccccccc-cccc-dddd-eeee-ffffffffffff"))
  private val authId4 = AuthId(UUID.fromString("dddddddd-cccc-dddd-eeee-ffffffffffff"))
  private val authId5 = AuthId(UUID.fromString("eeeeeeee-cccc-dddd-eeee-ffffffffffff"))
  private val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val userId3 = UserId(UUID.fromString("b077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val userId4 = UserId(UUID.fromString("c077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val userId5 = UserId(UUID.fromString("d077fb08-9935-4a6d-8643-bf97c073bf0f"))

  private val clientId = ClientId("test-client")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

  /** True if `t`, or any of its causes, is a Postgres unique-violation (SQLSTATE 23505). Used to
    * make sure the "rollback" tests actually fail for the reason we're forcing, not for some
    * unrelated bug that would make them pass vacuously.
    */
  private def isUniqueViolation(t: Throwable, depth: Int = 0): Boolean =
    depth < 10 && (t match
      case sql: SQLException => sql.getSQLState == "23505"
      case _ => Option(t.getCause).exists(isUniqueViolation(_, depth + 1))
    )

  private def newConversation(userId: UserId) = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("openid")),
    codeChallenge = codeChallenge,
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = None,
    userId = Some(userId),
    credential = None,
    step = ConversationStep.AccessDenied,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    userAgent = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "csrf",
    priorSessionId = None,
  )

  private def newCodeRecord(
      userId: UserId,
      sessionIdMac: MAC.Of[SessionId],
      publicSessionId: PublicSessionId,
      now: Instant,
  ) = AuthorizationCodeRecord(
    sessionId = sessionIdMac,
    publicSessionId = publicSessionId,
    clientId = clientId,
    userId = userId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("openid")),
    codeChallenge = codeChallenge,
    codeChallengeMethod = CodeChallengeMethod.S256,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    accessToken = AccessToken.fromString("access-token"),
    amr = Set.empty,
    authTime = now,
    acr = None,
  )

  private def newSession(userId: UserId, publicSessionId: PublicSessionId, now: Instant) =
    SessionRecord(
      userId = userId,
      clients = List(ClientEntry(clientId, now)),
      userAgent = UserAgentInfo.parse(None),
      createdAt = now,
      amr = Map.empty,
      publicId = publicSessionId,
    )

  private def newRefreshToken(
      userId: UserId,
      sessionIdMac: MAC.Of[SessionId],
      publicSessionId: PublicSessionId,
      now: Instant,
  ) = RefreshTokenRecord(
    sessionId = sessionIdMac,
    publicSessionId = publicSessionId,
    accessToken = AccessToken.fromString("prior-access-token"),
    userId = userId,
    clientId = clientId,
    externalAudience = Nil,
    scope = Set(ScopeToken("openid")),
    issuedAt = now,
    expiresAt = now.plusSeconds(3600),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    previousRefreshToken = None,
    amr = Set(AuthMethodRef.pwd),
    authTime = now,
    acr = None,
  )

  /** Builds a request for `finish`, defaulting the ttl/idle-ttl fields so each test only has to
    * spell out what it's actually varying.
    */
  private def newRequest(
      authId: AuthId,
      userId: UserId,
      codeMac: MAC.Of[AuthorizationCode],
      sessionIdMac: MAC.Of[SessionId],
      publicSessionId: PublicSessionId,
      now: Instant,
      priorSession: Option[PriorSession] = None,
  ) = FinishConversationRequest(
    authId = authId,
    version = 0L,
    codeMac = codeMac,
    codeRecord = newCodeRecord(userId, sessionIdMac, publicSessionId, now),
    codeTtl = 1.minute,
    sessionIdMac = sessionIdMac,
    publicSessionId = publicSessionId,
    session = newSession(userId, publicSessionId, now),
    sessionTtl = 1.hour,
    sessionIdleTtl = Some(30.minutes),
    priorSession = priorSession,
  )

  // Every test truncates the same shared tables (auth_conversations, authorization_codes,
  // sso_sessions, refresh_tokens) as its setup step. Without forcing sequential execution, ZIO
  // Test runs these in parallel by default, so one test's TRUNCATE can wipe out a row another
  // concurrently-running test just inserted (flaky, order-dependent failures).
  override val spec: Spec[TransactorZIO & TestEnvironment & Scope, Any] =
    suite("PostgresConversationFinalizerSpec")(
      test("commits delete + create code + create session together") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations, authorization_codes, sso_sessions, refresh_tokens".update.run())
          now <- Clock.instant
          finalizer = PostgresConversationFinalizer(xa)
          conversations = PostgresConversationRepository(xa)
          codes = PostgresAuthorizationCodeRepository(xa)
          sessions = PostgresSessionRepository(xa)
          _ <- conversations.create(authId1, newConversation(userId1), 15.minutes)
          codeMac = MAC(Array.fill(32)(1.toByte))
          sessionIdMac = MAC(Array.fill(32)(2.toByte))
          publicSessionId = PublicSessionId("public-1")
          claimed <- finalizer.finish(newRequest(authId1, userId1, codeMac, sessionIdMac, publicSessionId, now))
          conversationAfter <- conversations.find(authId1)
          codeAfter <- codes.find(codeMac)
          sessionAfter <- sessions.findSession(sessionIdMac)
        yield assertTrue(
          claimed,
          conversationAfter.isEmpty,
          codeAfter.isDefined,
          sessionAfter.isDefined,
        )
      },
      test("rolls back the conversation delete when session creation fails on a unique violation (issue #102)") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations, authorization_codes, sso_sessions, refresh_tokens".update.run())
          now <- Clock.instant
          finalizer = PostgresConversationFinalizer(xa)
          conversations = PostgresConversationRepository(xa)
          codes = PostgresAuthorizationCodeRepository(xa)
          _ <- conversations.create(authId2, newConversation(userId2), 15.minutes)
          conflictingPublicSessionId = PublicSessionId("duplicate-public-id")
          existingSessionIdMac = MAC(Array.fill(32)(8.toByte))
          // A pre-existing session with the same public_id (UNIQUE) makes the INSERT inside
          // finish() fail with a constraint violation. Before the fix this couldn't happen inside
          // one transaction with the delete, so the conversation delete would have already
          // committed on its own — this test asserts that no longer happens.
          _ <- PostgresSessionRepository(xa).create(
            existingSessionIdMac,
            conflictingPublicSessionId,
            newSession(userId2, conflictingPublicSessionId, now),
            1.hour,
            None,
            None,
          )
          codeMac = MAC(Array.fill(32)(3.toByte))
          sessionIdMac = MAC(Array.fill(32)(4.toByte))
          outcome <- finalizer.finish(newRequest(authId2, userId2, codeMac, sessionIdMac, conflictingPublicSessionId, now)).either
          conversationAfter <- conversations.find(authId2)
          codeAfter <- codes.find(codeMac)
        yield assertTrue(
          outcome.fold(isUniqueViolation(_), _ => false),
          conversationAfter.isDefined,
          codeAfter.isEmpty,
        )
      },
      test("rolls back the conversation delete when the authorization code insert fails on a unique violation (issue #102)") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations, authorization_codes, sso_sessions, refresh_tokens".update.run())
          now <- Clock.instant
          finalizer = PostgresConversationFinalizer(xa)
          conversations = PostgresConversationRepository(xa)
          codes = PostgresAuthorizationCodeRepository(xa)
          sessions = PostgresSessionRepository(xa)
          _ <- conversations.create(authId3, newConversation(userId3), 15.minutes)
          conflictingCodeMac = MAC(Array.fill(32)(9.toByte))
          otherSessionIdMac = MAC(Array.fill(32)(10.toByte))
          otherPublicSessionId = PublicSessionId("other-public-session")
          // A pre-existing authorization_codes row with the same `code` PK makes the INSERT
          // inside finish() fail before it ever reaches the session INSERT — this is the other
          // failure point named in issue #102 (the finalizer's own regression test previously
          // only exercised the session-insert failure, not this one).
          _ <- codes.create(conflictingCodeMac, newCodeRecord(userId3, otherSessionIdMac, otherPublicSessionId, now), 1.minute)
          sessionIdMac = MAC(Array.fill(32)(11.toByte))
          publicSessionId = PublicSessionId("public-3")
          outcome <- finalizer.finish(newRequest(authId3, userId3, conflictingCodeMac, sessionIdMac, publicSessionId, now)).either
          conversationAfter <- conversations.find(authId3)
          sessionAfter <- sessions.findSession(sessionIdMac)
        yield assertTrue(
          outcome.fold(isUniqueViolation(_), _ => false),
          conversationAfter.isDefined,
          sessionAfter.isEmpty,
        )
      },
      test("invalidates the prior session and its refresh token on PriorSession.Invalidate") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations, authorization_codes, sso_sessions, refresh_tokens".update.run())
          now <- Clock.instant
          finalizer = PostgresConversationFinalizer(xa)
          conversations = PostgresConversationRepository(xa)
          sessions = PostgresSessionRepository(xa)
          _ <- conversations.create(authId4, newConversation(userId4), 15.minutes)
          priorSessionIdMac = MAC(Array.fill(32)(20.toByte))
          priorPublicSessionId = PublicSessionId("prior-public-session-invalidate")
          _ <- sessions.create(priorSessionIdMac, priorPublicSessionId, newSession(userId4, priorPublicSessionId, now), 1.hour, None, None)
          priorRefreshTokenMac = MAC(Array.fill(32)(21.toByte))
          _ <- sessions.createRefreshToken(priorRefreshTokenMac, newRefreshToken(userId4, priorSessionIdMac, priorPublicSessionId, now))
            .mapError(e => new RuntimeException(e.toString))
          codeMac = MAC(Array.fill(32)(22.toByte))
          newSessionIdMac = MAC(Array.fill(32)(23.toByte))
          newPublicSessionId = PublicSessionId("public-4")
          claimed <- finalizer.finish(
            newRequest(
              authId4,
              userId4,
              codeMac,
              newSessionIdMac,
              newPublicSessionId,
              now,
              priorSession = Some(PriorSession.Invalidate(priorSessionIdMac)),
            ),
          )
          priorSessionAfter <- sessions.findSession(priorSessionIdMac)
          priorTokenAfter <- sessions.findToken(priorRefreshTokenMac)
          newSessionAfter <- sessions.findSession(newSessionIdMac)
        yield assertTrue(
          claimed,
          priorSessionAfter.isEmpty,
          priorTokenAfter.isEmpty,
          newSessionAfter.isDefined,
        )
      },
      test("migrates the prior session's refresh token to the new session on PriorSession.MigrateTokens") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations, authorization_codes, sso_sessions, refresh_tokens".update.run())
          now <- Clock.instant
          finalizer = PostgresConversationFinalizer(xa)
          conversations = PostgresConversationRepository(xa)
          sessions = PostgresSessionRepository(xa)
          _ <- conversations.create(authId5, newConversation(userId5), 15.minutes)
          priorSessionIdMac = MAC(Array.fill(32)(30.toByte))
          priorPublicSessionId = PublicSessionId("prior-public-session-migrate")
          _ <- sessions.create(priorSessionIdMac, priorPublicSessionId, newSession(userId5, priorPublicSessionId, now), 1.hour, None, None)
          priorRefreshTokenMac = MAC(Array.fill(32)(31.toByte))
          _ <- sessions.createRefreshToken(priorRefreshTokenMac, newRefreshToken(userId5, priorSessionIdMac, priorPublicSessionId, now))
            .mapError(e => new RuntimeException(e.toString))
          codeMac = MAC(Array.fill(32)(32.toByte))
          newSessionIdMac = MAC(Array.fill(32)(33.toByte))
          newPublicSessionId = PublicSessionId("public-5")
          migratedAmr = Set(AuthMethodRef.otp)
          migratedAcr = Some(Acr("urn:test:step-up"))
          claimed <- finalizer.finish(
            newRequest(
              authId5,
              userId5,
              codeMac,
              newSessionIdMac,
              newPublicSessionId,
              now,
              priorSession = Some(PriorSession.MigrateTokens(priorSessionIdMac, migratedAmr, now, migratedAcr)),
            ),
          )
          priorSessionAfter <- sessions.findSession(priorSessionIdMac)
          migratedTokenAfter <- sessions.findToken(priorRefreshTokenMac)
        yield assertTrue(
          claimed,
          priorSessionAfter.isEmpty,
          migratedTokenAfter.exists { t =>
            java.util.Arrays.equals(t.sessionId: Array[Byte], newSessionIdMac: Array[Byte]) &&
            t.amr == migratedAmr &&
            t.acr == migratedAcr
          },
        )
      },
    ).@@(TestAspect.sequential)
