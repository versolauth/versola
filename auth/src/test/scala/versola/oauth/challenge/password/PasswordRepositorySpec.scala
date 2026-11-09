package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.challenge.password.model.{PasswordChangeEffect, PasswordHistoryChanged}
import versola.oauth.client.model.{AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{
  ClientEntry,
  PublicSessionId,
  RefreshAlreadyExchanged,
  RefreshTokenFamilyId,
  RefreshTokenRecord,
  SessionRecord,
  UserAgentId,
}
import versola.user.model.UserId
import versola.util.revocation.{RevocationCursor, RevocationKey, RevocationRepository}
import versola.util.{DatabaseSpecBase, MAC, Salt, Secret}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

trait PasswordRepositorySpec extends DatabaseSpecBase[PasswordRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001"))
  val userId2 = UserId(UUID.fromString("a2000000-0000-0000-0000-000000000002"))

  val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

  val userAgentId1 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
  val userAgentId2 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
  val clientId1 = ClientId("client-1")

  /** The widest TTL, which is all a password change knows about the tokens a user holds. */
  val terms = RevocationTerms(
    cause = RevocationCause.PasswordChanged,
    ttl = 10.minutes,
    tenantId = None,
    ttlByClient = Map(clientId1 -> 5.minutes),
  )

  def recorded(env: PasswordRepositorySpec.Env) =
    env.revocations.activeSince(RevocationCursor.Beginning, 100).map(_.revocations.map(r => r.key -> r).toMap)

  /** A live session of `userId`, with the key it is stored under, and a refresh token held under it. */
  case class Seeded(mac: MAC, publicId: PublicSessionId, token: MAC)

  def refreshTokenOf(userId: UserId, session: MAC, publicId: PublicSessionId, n: Int, now: java.time.Instant) =
    RefreshTokenRecord(
      familyId = RefreshTokenFamilyId(s"family-$n"),
      sessionId = session,
      publicSessionId = publicId,
      userId = userId,
      clientId = clientId1,
      audience = List.empty,
      authorizationDetails = None,
      scope = Set(ScopeToken("read")),
      issuedAt = now,
      expiresAt = now.plusSeconds(30.days.toSeconds),
      requestedClaims = None,
      uiLocales = None,
      nonce = None,
      amr = Set(AuthMethodRef.pwd),
      authTime = now,
      acr = None,
      cnf = None,
    )

  def seedSession(
      env: PasswordRepositorySpec.Env,
      userId: UserId,
      agent: UserAgentId,
      n: Int,
  ): ZIO[Any, Throwable | RefreshAlreadyExchanged, Seeded] =
    val seeded = Seeded(MAC(Array.fill(32)(n.toByte)), PublicSessionId(s"public-session-$n"), MAC(Array.fill(32)((100 + n).toByte)))
    for
      now <- Clock.instant
      _ <- env.sessions.create(
        seeded.mac,
        SessionRecord(
          userId = userId,
          clients = List(ClientEntry(clientId1, now)),
          userAgentId = agent,
          createdAt = now,
          amr = Map.empty,
          publicId = seeded.publicId,
          expiresAt = now,
        ),
        1.hour,
        None,
        None,
      )
      _ <- env.sessions.createRefreshToken(seeded.token, None, refreshTokenOf(userId, seeded.mac, seeded.publicId, n, now), None)
    yield seeded

  def pass(n: Int): Secret = Secret(Array.fill(16)(n.toByte))
  def salt(n: Int): Salt = Salt(Array.fill(16)(n.toByte))

  /** A change as the service makes it: against the newest permanent password it has just read. */
  def createChecked(
      env: PasswordRepositorySpec.Env,
      userId: UserId,
      password: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
  ) =
    for
      permanents <- env.repository.list(userId).map(_.filter(_.expiresAt.isEmpty))
      _ <- env.repository.create(
        userId,
        password,
        salt,
        historySize,
        numDifferent,
        expectedHead = permanents.map(_.id).maxOption,
        change = PasswordChangeEffect.Untouched,
      )
    yield ()

  def testCases(env: PasswordRepositorySpec.Env): List[Spec[PasswordRepositorySpec.Env & Scope, Any]] =
    List(
      test("create then list returns the record for the correct user") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 3)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 1,
          rows.head.userId == userId1,
          rows.head.createdAt == baseInstant,
          rows.head.expiresAt == None,
        )
      },
      test("list is ordered newest first by created_at DESC, id DESC") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          _ <- TestClock.adjust(1.second)
          _ <- createChecked(env, userId1, pass(2), salt(2), 5, 1)
          _ <- TestClock.adjust(1.second)
          _ <- createChecked(env, userId1, pass(3), salt(3), 5, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 3,
          rows(0).createdAt == baseInstant.plusSeconds(2),
          rows(1).createdAt == baseInstant.plusSeconds(1),
          rows(2).createdAt == baseInstant,
        )
      },
      test("list uses id DESC as tiebreaker when created_at timestamps are equal") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          _ <- createChecked(env, userId1, pass(2), salt(2), 5, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(
          rows.size == 2,
          rows(0).id > rows(1).id,
        )
      },
      test("prune keeps exactly historySize rows after insert") {
        for
          _ <- createChecked(env, userId1, pass(1), salt(1), 3, 1)
          _ <- createChecked(env, userId1, pass(2), salt(2), 3, 1)
          _ <- createChecked(env, userId1, pass(3), salt(3), 3, 1)
          _ <- createChecked(env, userId1, pass(4), salt(4), 3, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 3)
      },
      test("prune removes multiple excess entries when historySize is reduced") {
        for
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          _ <- createChecked(env, userId1, pass(2), salt(2), 5, 1)
          _ <- createChecked(env, userId1, pass(3), salt(3), 5, 1)
          _ <- createChecked(env, userId1, pass(4), salt(4), 2, 1)
          rows <- env.repository.list(userId1)
        yield assertTrue(rows.size == 2)
      },
      test("prune is deterministic: keeps highest id when created_at timestamps are equal") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 1, 1)
          afterFirst <- env.repository.list(userId1)
          idOfFirst = afterFirst.head.id
          _ <- createChecked(env, userId1, pass(2), salt(2), 1, 1)
          remaining <- env.repository.list(userId1)
        yield assertTrue(
          remaining.size == 1,
          remaining.head.id > idOfFirst,
        )
      },
      test("histories are isolated between users") {
        for
          _ <- createChecked(env, userId1, pass(1), salt(1), 1, 1)
          _ <- createChecked(env, userId2, pass(2), salt(2), 1, 1)
          rows1 <- env.repository.list(userId1)
          rows2 <- env.repository.list(userId2)
        yield assertTrue(rows1.size == 1, rows2.size == 1)
      },

      // Concurrency: two simultaneous changes that both checked an empty history. Without the lock both
      // insert; with it the second sees the first's row and is refused, to check again.
      test("of two concurrent changes made against the same history only one is stored") {
        for
          results <- ZIO.collectAllPar(List(
            env.repository.create(
              userId1,
              pass(1),
              salt(1),
              historySize = 1,
              numDifferent = 1,
              expectedHead = None,
              change = PasswordChangeEffect.Untouched,
            ).either,
            env.repository.create(
              userId1,
              pass(2),
              salt(2),
              historySize = 1,
              numDifferent = 1,
              expectedHead = None,
              change = PasswordChangeEffect.Untouched,
            ).either,
          ))
          rows <- env.repository.list(userId1)
        yield assertTrue(
          results.count(_.isRight) == 1,
          results.count(_ == Left(PasswordHistoryChanged)) == 1,
          rows.size == 1,
        )
      },
      test("a change made against a history that has since moved on is refused") {
        for
          _ <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.Untouched,
          )
          stale <- env.repository.create(
            userId1,
            pass(2),
            salt(2),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.Untouched,
          ).either
          rows <- env.repository.list(userId1)
        yield assertTrue(stale == Left(PasswordHistoryChanged), rows.size == 1)
      },

      // First-login token of the bootstrap admin (issue #824).
      test("a bootstrap credential is created once; a second attempt finds a usable one and changes nothing") {
        for
          _ <- TestClock.setTime(baseInstant)
          first <- bootstrap(env, userId1, token(1))
          second <- bootstrap(env, userId1, token(2))
          rows <- env.repository.list(userId1)
          viaFirst <- consume(env, token(1))
          viaSecond <- consume(env, token(2))
        yield assertTrue(first, !second, rows.size == 1, viaFirst == Some(userId1), viaSecond.isEmpty)
      },
      test("of several replicas bootstrapping at once exactly one issues the credential and the link") {
        for
          _ <- TestClock.setTime(baseInstant)
          results <- ZIO.collectAllPar((1 to 6).toList.map(n => bootstrap(env, userId1, token(n))))
          rows <- env.repository.list(userId1)
        yield assertTrue(results.count(identity) == 1, rows.size == 1)
      },
      test("a token is spent by its first use only") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          first <- consume(env, token(1))
          again <- consume(env, token(1))
        yield assertTrue(first == Some(userId1), again.isEmpty)
      },
      test("two concurrent uses of one token: only one wins") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          results <- ZIO.collectAllPar(List.fill(4)(consume(env, token(1))))
        yield assertTrue(results.count(_.isDefined) == 1)
      },
      test("an unknown token spends nothing") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          unknown <- consume(env, token(9))
          stillThere <- consume(env, token(1))
        yield assertTrue(unknown.isEmpty, stillThere == Some(userId1))
      },
      test("an expired token is refused") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- TestClock.adjust(25.hours)
          expired <- consume(env, token(1))
        yield assertTrue(expired.isEmpty)
      },
      test("an expired temporary password is replaced, together with its token, on the next bootstrap") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- TestClock.adjust(25.hours)
          again <- bootstrap(env, userId1, token(2))
          rows <- env.repository.list(userId1)
          old <- consume(env, token(1))
          fresh <- consume(env, token(2))
        yield assertTrue(again, rows.size == 1, old.isEmpty, fresh == Some(userId1))
      },
      test("setting a permanent password kills the token") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          afterSet <- consume(env, token(1))
        yield assertTrue(afterSet.isEmpty)
      },
      test("a bootstrap is not issued for a user who has a permanent password") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          created <- bootstrap(env, userId1, token(1))
          viaToken <- consume(env, token(1))
        yield assertTrue(!created, viaToken.isEmpty)
      },
      test("reset drops the unused temporary credential and its token; the next bootstrap issues new ones") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          reset <- env.repository.resetBootstrapCredential(userId1)
          rowsAfterReset <- env.repository.list(userId1)
          oldToken <- consume(env, token(1))
          again <- bootstrap(env, userId1, token(2))
          newToken <- consume(env, token(2))
        yield assertTrue(reset, rowsAfterReset.isEmpty, oldToken.isEmpty, again, newToken == Some(userId1))
      },
      test("reset is refused once a permanent password exists and deletes nothing") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- createChecked(env, userId1, pass(1), salt(1), 5, 1)
          reset <- env.repository.resetBootstrapCredential(userId1)
          rows <- env.repository.list(userId1)
        yield assertTrue(!reset, rows.size == 1, rows.head.expiresAt.isEmpty)
      },
      test("replacing or deleting the temporary password revokes the first-login token issued with it") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- env.repository.createTemporary(userId1, pass(2), salt(2), baseInstant.plusSeconds(3600), PasswordChangeEffect.Untouched)
          afterReplace <- consume(env, token(1))
          _ <- bootstrap(env, userId2, token(2))
          _ <- env.repository.deleteTemporary(userId2)
          afterDelete <- consume(env, token(2))
        yield assertTrue(afterReplace.isEmpty, afterDelete.isEmpty)
      },
      test("a spent link says what it was for") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          spent <- env.repository.consumeLink(token(1), Set(OneTimeLinkPurpose.FirstLogin))
        yield assertTrue(spent == Some(ConsumedLink(userId1, OneTimeLinkPurpose.FirstLogin)))
      },
      test("a link of a purpose the caller does not accept is neither spent nor reported") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          refused <- env.repository.consumeLink(token(1), Set.empty)
          stillThere <- env.repository.consumeLink(token(1), Set(OneTimeLinkPurpose.FirstLogin))
        yield assertTrue(refused.isEmpty, stillThere.map(_.userId) == Some(userId1))
      },
      test("a token is bound to its user: another user's reset leaves it alone") {
        for
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- env.repository.resetBootstrapCredential(userId2)
          stillThere <- consume(env, token(1))
        yield assertTrue(stillThere == Some(userId1))
      },
      // What changing the password does besides storing it, decided by who changes it.
      test("an administrator's change records the user, bounded at the change, with the password") {
        for
          now <- Clock.instant
          _ <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.RefuseEarlierTokens(terms),
          )
          entries <- recorded(env)
          entry = entries.get(RevocationKey.Sub(userId1.toString))
        yield assertTrue(
          entries.keySet == Set(RevocationKey.Sub(userId1.toString)),
          entry.exists(_.issuedBefore.contains(now)),
          entry.exists(_.expiresAt == now.plus(terms.ttl)),
        )
      },
      test("a change that asks for nothing more records nothing") {
        for
          ended <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.Untouched,
          )
          entries <- recorded(env)
        yield assertTrue(ended.isEmpty, entries.isEmpty)
      },
      test("an administrator's reset ends every session and refresh token of the user with the temporary password, and records the user") {
        for
          now <- Clock.instant
          first <- seedSession(env, userId1, userAgentId1, 1)
          second <- seedSession(env, userId1, userAgentId1, 2)
          someoneElses <- seedSession(env, userId2, userAgentId2, 3)
          ended <- env.repository.createTemporary(userId1, pass(1), salt(1), now.plusSeconds(3600), PasswordChangeEffect.EndAllSessions(terms))
          entries <- recorded(env)
          sessions <- ZIO.foreach(List(first, second, someoneElses))(seeded => env.sessions.findSession(seeded.mac))
          tokens <- ZIO.foreach(List(first, second, someoneElses))(seeded => env.sessions.findToken(seeded.token))
          mine = entries.get(RevocationKey.Sub(userId1.toString))
        yield assertTrue(
          // every session of the user and nobody else's, with the refresh tokens held under them
          ended.map(_.publicId).toSet == Set(first.publicId, second.publicId),
          sessions.map(_.isDefined) == List(false, false, true),
          tokens.map(_.isDefined) == List(false, false, true),
          // the user, bounded at the reset: a token issued before it is refused, one issued by the next sign-in is not
          entries.keySet == Set(RevocationKey.Sub(userId1.toString)),
          mine.exists(_.issuedBefore.contains(now)),
        )
      },
      test("the bootstrap's temporary password ends nothing and records nothing") {
        for
          now <- Clock.instant
          session <- seedSession(env, userId1, userAgentId1, 1)
          ended <- env.repository.createTemporary(userId1, pass(1), salt(1), now.plusSeconds(3600), PasswordChangeEffect.Untouched)
          entries <- recorded(env)
          after <- env.sessions.findSession(session.mac)
        yield assertTrue(ended.isEmpty, entries.isEmpty, after.isDefined)
      },
      test("a change refused because the history moved on records nothing") {
        for
          _ <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.Untouched,
          )
          stale <- env.repository.create(
            userId1,
            pass(2),
            salt(2),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.RefuseEarlierTokens(terms),
          ).either
          entries <- recorded(env)
        yield assertTrue(stale == Left(PasswordHistoryChanged), entries.isEmpty)
      },
      test("ending the other sessions ends every session of the user but the current one, with its refresh tokens, and records each") {
        for
          now <- Clock.instant
          current <- seedSession(env, userId1, userAgentId1, 1)
          other <- seedSession(env, userId1, userAgentId1, 2)
          another <- seedSession(env, userId1, userAgentId1, 3)
          someoneElses <- seedSession(env, userId2, userAgentId2, 4)
          ended <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.EndOtherSessions(current.publicId, terms),
          )
          entries <- recorded(env)
          currentAfter <- env.sessions.findSession(current.mac)
          otherAfter <- env.sessions.findSession(other.mac)
          anotherAfter <- env.sessions.findSession(another.mac)
          someoneElsesAfter <- env.sessions.findSession(someoneElses.mac)
          tokens <- ZIO.foreach(List(current, other, another, someoneElses))(seeded => env.sessions.findToken(seeded.token))
          passwords <- env.repository.list(userId1)
        yield assertTrue(
          passwords.size == 1,
          ended.map(_.publicId).toSet == Set(other.publicId, another.publicId),
          currentAfter.isDefined,
          otherAfter.isEmpty,
          anotherAfter.isEmpty,
          someoneElsesAfter.isDefined,
          tokens.map(_.isDefined) == List(true, false, false, true),
          // `sid`, no issuance bound: the entry names a session that no longer exists, and it lasts as long as the
          // longest-lived client the session served can have issued a token.
          entries.keySet == Set(RevocationKey.Sid(other.publicId), RevocationKey.Sid(another.publicId)),
          entries.values.forall(_.issuedBefore.isEmpty),
          entries.values.forall(_.expiresAt == now.plus(5.minutes)),
        )
      },
      test("ending the other sessions reaches a refresh token that outlived its session, and still spares the current one") {
        for
          current <- seedSession(env, userId1, userAgentId1, 1)
          now <- Clock.instant
          // A session that is gone on its own account (nothing stored for it) with a refresh token of its own expiry:
          // the one an intruder would keep using.
          orphan = Seeded(MAC(Array.fill(32)(99.toByte)), PublicSessionId("public-session-gone"), MAC(Array.fill(32)(109.toByte)))
          _ <- env.sessions.createRefreshToken(orphan.token, None, refreshTokenOf(userId1, orphan.mac, orphan.publicId, 9, now), None)
          _ <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.EndOtherSessions(current.publicId, terms),
          )
          kept <- env.sessions.findToken(current.token)
          dropped <- env.sessions.findToken(orphan.token)
        yield assertTrue(kept.isDefined, dropped.isEmpty)
      },
      test("a change that does not ask to end the other sessions leaves them alone") {
        for
          current <- seedSession(env, userId1, userAgentId1, 1)
          other <- seedSession(env, userId1, userAgentId1, 2)
          _ <- env.repository.create(
            userId1,
            pass(1),
            salt(1),
            historySize = 5,
            numDifferent = 1,
            expectedHead = None,
            change = PasswordChangeEffect.Untouched,
          )
          currentAfter <- env.sessions.findSession(current.mac)
          otherAfter <- env.sessions.findSession(other.mac)
          token <- env.sessions.findToken(other.token)
          entries <- recorded(env)
        yield assertTrue(currentAfter.isDefined, otherAfter.isDefined, token.isDefined, entries.isEmpty)
      },
    )

  def token(n: Int): Array[Byte] = Array.fill(32)(n.toByte)

  /** Whose link was spent, leaving out what it was for (the purpose has its own test). */
  def consume(env: PasswordRepositorySpec.Env, tokenHash: Array[Byte]) =
    env.repository.consumeLink(tokenHash, Set(OneTimeLinkPurpose.FirstLogin)).map(_.map(_.userId))

  /** What auth's bootstrap does on start: a temporary password good for 24 hours and a token valid as long. */
  def bootstrap(env: PasswordRepositorySpec.Env, userId: UserId, tokenHash: Array[Byte]) =
    Clock.instant.flatMap: now =>
      env.repository.createBootstrapCredential(
        userId = userId,
        passwordHash = pass(7),
        salt = salt(7),
        expiresAt = now.plusSeconds(24 * 3600),
        linkTokenHash = Some(tokenHash),
      )

object PasswordRepositorySpec:
  case class Env(repository: PasswordRepository, sessions: SessionRepository, revocations: RevocationRepository)
